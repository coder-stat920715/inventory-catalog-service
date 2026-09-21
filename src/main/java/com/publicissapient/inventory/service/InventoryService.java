package com.publicissapient.inventory.service;

import com.publicissapient.inventory.model.Product;
import com.publicissapient.inventory.pubsub.CacheInvalidationPublisher;
import com.publicissapient.inventory.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Business-facing service. Demonstrates the DECLARATIVE (annotation-based) side of the
 * caching story, in contrast to L1L2CacheService's fully manual/explicit approach - an
 * interviewer will typically want to see both.
 *
 * - @Cacheable / @CachePut / @CacheEvict here operate against the @Primary CacheManager,
 *   which is the jittered RedisCacheManager configured in CacheConfig (i.e. L2 only - these
 *   annotations do NOT know about L1 at all, which is exactly why L1L2CacheService exists as
 *   a separate, explicit composite path for the hottest read endpoint).
 * - Redis Hash (HSET/HGET) is used for the "update just the stock level" operation, so we
 *   avoid a read-modify-write of the entire serialized Product JSON blob for a one-field change.
 * - Redis Sorted Set (ZSET) backs the trending-products leaderboard (ZINCRBY on each view /
 *   purchase event, ZREVRANGE to read the current top N).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private static final String TRENDING_ZSET_KEY = "trending:products";
    private static final String STOCK_HASH_KEY_PREFIX = "stock:hash:"; // one hash per product

    private final ProductRepository productRepository;
    private final L1L2CacheService l1L2CacheService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheInvalidationPublisher invalidationPublisher;

    // ------------------------------------------------------------------
    // Declarative caching (@Cacheable / @CachePut / @CacheEvict) - L2 only
    // ------------------------------------------------------------------

    /**
     * Simple annotation-driven read-through cache. Cache name "product-details" maps to the
     * 10-minute (jittered) TTL configured in application.yml under app.cache.l2.ttls.
     *
     * Contrast with L1L2CacheService.getProduct(id), which additionally gives you L1,
     * stampede locking and bloom-filter penetration protection - use THIS simpler annotated
     * path for lower-traffic lookups where that extra machinery isn't worth the complexity.
     */
    @Cacheable(cacheNames = "product-details", key = "#productId", unless = "#result == null")
    public Product getProductAnnotated(String productId) {
        log.info("CACHE MISS (annotated) - loading product '{}' from DB", productId);
        return productRepository.findById(productId).orElse(null);
    }

    /**
     * @CachePut always executes the method body AND refreshes the cache with its return value -
     * unlike @Cacheable it never short-circuits on a cache hit. Correct choice for "I know I'm
     * changing this row, update the cache to match" rather than "give me the cached value if we
     * have one".
     */
    @CachePut(cacheNames = "product-details", key = "#product.id")
    public Product updateProductAnnotated(Product product) {
        product.setLastUpdated(Instant.now());
        Product saved = productRepository.save(product);
        invalidationPublisher.publishEvict("product-details", saved.getId());
        return saved;
    }

    @CacheEvict(cacheNames = "product-details", key = "#productId")
    public void deleteProductAnnotated(String productId) {
        productRepository.deleteById(productId);
        invalidationPublisher.publishEvict("product-details", productId);
    }

    // ------------------------------------------------------------------
    // Composite L1+L2 path (delegates to L1L2CacheService - see that class for the
    // stampede/bloom-filter/circuit-breaker mechanics)
    // ------------------------------------------------------------------

    public Optional<Product> getProduct(String productId) {
        return l1L2CacheService.getProduct(productId);
    }

    public Product createOrUpdateProduct(Product product) {
        return l1L2CacheService.putProduct(product);
    }

    public void deleteProduct(String productId) {
        productRepository.deleteById(productId);
        l1L2CacheService.evictProduct(productId);
    }

    // ------------------------------------------------------------------
    // Redis Hash: partial stock updates without touching the full product JSON blob
    // ------------------------------------------------------------------

    /**
     * Stores per-product stock fields in their own Redis Hash (HSET), separate from the
     * fully-serialized Product cached above. This lets high-frequency stock decrements
     * (every order line item) avoid deserializing/reserializing the whole Product object.
     */
    public void updateStockLevelPartial(String productId, int newStockLevel) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        String hashKey = STOCK_HASH_KEY_PREFIX + productId;

        Map<String, String> fields = new HashMap<>();
        fields.put("stockLevel", String.valueOf(newStockLevel));
        fields.put("lastUpdated", Instant.now().toString());
        hashOps.putAll(hashKey, fields); // single HSET (multi-field) round trip

        // Keep the DB as source of truth in sync too (in a real system this would likely be
        // async/event-driven; done synchronously here for clarity).
        productRepository.findById(productId).ifPresent(p -> {
            p.setStockLevel(newStockLevel);
            p.setLastUpdated(Instant.now());
            productRepository.save(p);
        });

        // The cached full-product blob (both L1 and L2) is now stale - evict it. We deliberately
        // evict rather than @CachePut here: recomputing the WHOLE product JSON for a stock-only
        // change is exactly the cost this Hash-based path exists to avoid.
        l1L2CacheService.evictProduct(productId);

        log.debug("Partial stock update via HSET: productId='{}' newStock={}", productId, newStockLevel);
    }

    /** HINCRBY - atomic, lock-free decrement, ideal for concurrent order processing. */
    public long decrementStockAtomic(String productId, int quantity) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        String hashKey = STOCK_HASH_KEY_PREFIX + productId;
        Long remaining = hashOps.increment(hashKey, "stockLevel", -quantity); // HINCRBY under the hood
        l1L2CacheService.evictProduct(productId); // full-object cache is now stale
        return remaining == null ? 0 : remaining;
    }

    public Optional<Integer> getStockLevelFromHash(String productId) {
        HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
        String value = hashOps.get(STOCK_HASH_KEY_PREFIX + productId, "stockLevel");
        return value == null ? Optional.empty() : Optional.of(Integer.parseInt(value));
    }

    // ------------------------------------------------------------------
    // Redis Sorted Set (ZSET): real-time trending products leaderboard
    // ------------------------------------------------------------------

    /** Call on every product view/purchase event - ZINCRBY under the hood. */
    public void recordProductView(String productId) {
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        zSetOps.incrementScore(TRENDING_ZSET_KEY, productId, 1.0);
    }

    public void recordProductPurchase(String productId, int quantity) {
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        // Weight purchases higher than plain views for the trending signal
        zSetOps.incrementScore(TRENDING_ZSET_KEY, productId, quantity * 5.0);
    }

    /** ZREVRANGE WITHSCORES equivalent - top N by descending score. */
    public Set<ZSetOperations.TypedTuple<Object>> getTrendingProducts(int topN) {
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        return zSetOps.reverseRangeWithScores(TRENDING_ZSET_KEY, 0, topN - 1);
    }

    /** ZSCORE - current trending score for a single product. */
    public Double getTrendingScore(String productId) {
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        return zSetOps.score(TRENDING_ZSET_KEY, productId);
    }

    /** ZRANK - a product's current rank (0-based, ascending) in the trending set. */
    public Long getTrendingRank(String productId) {
        ZSetOperations<String, Object> zSetOps = redisTemplate.opsForZSet();
        Long ascRank = zSetOps.rank(TRENDING_ZSET_KEY, productId);
        if (ascRank == null) return null;
        Long size = zSetOps.zCard(TRENDING_ZSET_KEY);
        return size == null ? null : size - 1 - ascRank; // convert to descending (1st place = 0)
    }
}
