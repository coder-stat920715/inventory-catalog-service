package com.publicissapient.inventory.service;

import com.publicissapient.inventory.config.CacheConfig;
import com.publicissapient.inventory.model.Product;
import com.publicissapient.inventory.pubsub.CacheInvalidationPublisher;
import com.publicissapient.inventory.repository.ProductRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Hand-rolled composite cache that gives us full explicit control over the read/write path,
 * as an alternative to (and complementary with) the declarative @Cacheable approach used in
 * InventoryService. This is the class to walk an interviewer through line-by-line.
 *
 * Read path:
 *   1. L1 (Caffeine, in-JVM)   - sub-millisecond, no network hop
 *   2. L2 (Redis, distributed) - shared across all instances, ~0.5-2ms network round trip
 *   3. DB (source of truth)    - guarded by a Redisson distributed lock so that, under a
 *                                 thundering-herd of concurrent misses for the SAME key, only
 *                                 one request per cluster actually queries the database.
 *   4. Backfill L2 then L1, then publish a Pub/Sub invalidation-free "informational" event is
 *      NOT needed on the read path (nothing became stale) - invalidation events are only
 *      published on WRITE (see evictProduct/putProduct below).
 *
 * Penetration protection:
 *   - An RBloomFilter of all known product IDs is checked FIRST. A negative bloom-filter
 *     result is a guaranteed miss (no false negatives) so we short-circuit straight to
 *     "not found" without touching Redis or the DB at all - this is what actually stops a
 *     malicious/broken client hammering random non-existent IDs from ever reaching the DB.
 *   - As a second layer (for legitimate IDs that simply don't exist, e.g. a raced delete),
 *     we also cache a short-TTL "not found" sentinel in a dedicated "product-not-found" cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class L1L2CacheService {

    private static final String L2_CACHE_NAME = "product-details";
    private static final String NOT_FOUND_CACHE_NAME = "product-not-found";
    private static final Object NOT_FOUND_SENTINEL = new Object();

    private final CacheManager l1CacheManager; // Caffeine ("l1CacheManager" qualifier bean)
    private final CacheManager redisCacheManager; // Redis (@Primary CacheManager bean)
    private final ProductRepository productRepository;
    private final RedissonClient redissonClient;
    private final CacheInvalidationPublisher invalidationPublisher;
    private final CacheConfig.CacheProperties cacheProperties;

    private RBloomFilter<String> productIdBloomFilter;

    @PostConstruct
    void initBloomFilter() {
        productIdBloomFilter = redissonClient.getBloomFilter(cacheProperties.getBloomFilter().getName());
        // tryInit is a no-op if the filter already exists in Redis (e.g. after a restart) -
        // Bloom filter parameters can't be changed after creation without deleting the key.
        productIdBloomFilter.tryInit(
                cacheProperties.getBloomFilter().getExpectedInsertions(),
                cacheProperties.getBloomFilter().getFalsePositiveRate());
        // Warm the filter from the DB on startup. In real prod this is done as a batch job /
        // migration step, not synchronously in @PostConstruct, but it's inlined here for clarity.
        productRepository.findAll().forEach(p -> productIdBloomFilter.add(p.getId()));
        log.info("Bloom filter '{}' initialized with ~{} known product IDs",
                cacheProperties.getBloomFilter().getName(), productIdBloomFilter.count());
    }

    // ------------------------------------------------------------------
    // READ PATH
    // ------------------------------------------------------------------

    public Optional<Product> getProduct(String productId) {

        // --- Layer 0: Bloom filter penetration guard -------------------------------------
        if (!productIdBloomFilter.contains(productId)) {
            log.debug("Bloom filter negative for id='{}' - short-circuiting, no cache/DB hit", productId);
            return Optional.empty();
        }

        // --- Layer 1: L1 (Caffeine) ---------------------------------------------------------
        Cache l1 = l1CacheManager.getCache(L2_CACHE_NAME);
        if (l1 != null) {
            Product l1Hit = l1.get(productId, Product.class);
            if (l1Hit != null) {
                log.debug("L1 HIT id='{}'", productId);
                return Optional.of(l1Hit);
            }
        }

        // --- Layer 1b: negative cache (explicit null-value caching) ------------------------
        Cache notFoundCache = redisCacheManager.getCache(NOT_FOUND_CACHE_NAME);
        if (notFoundCache != null && notFoundCache.get(productId) != null) {
            log.debug("Negative-cache HIT id='{}' - known not-found, skipping DB", productId);
            return Optional.empty();
        }

        // --- Layer 2: L2 (Redis) -------------------------------------------------------------
        Product l2Hit = readFromL2(productId);
        if (l2Hit != null) {
            log.debug("L2 HIT id='{}' - backfilling L1", productId);
            if (l1 != null) {
                l1.put(productId, l2Hit);
            }
            return Optional.of(l2Hit);
        }

        // --- Layer 3: DB, guarded by distributed lock (stampede protection) ------------------
        return loadFromDbWithStampedeProtection(productId);
    }

    /**
     * Wrapped with a circuit breaker: if Redis itself is down/unreachable, RedisCacheManager
     * throws a RuntimeException from get(); rather than let that propagate as a 500, we treat
     * it as an L2 miss and fall through to the DB. See CacheErrorHandler for the annotation-
     * driven equivalent used by InventoryService's @Cacheable methods.
     */
    @CircuitBreaker(name = "redisCache", fallbackMethod = "readFromL2Fallback")
    Product readFromL2(String productId) {
        Cache l2 = redisCacheManager.getCache(L2_CACHE_NAME);
        return l2 == null ? null : l2.get(productId, Product.class);
    }

    @SuppressWarnings("unused")
    Product readFromL2Fallback(String productId, Throwable t) {
        log.warn("L2 (Redis) unavailable for id='{}', degrading straight to DB. cause={}", productId, t.toString());
        return null; // treat as miss -> caller proceeds to DB
    }

    /**
     * Double-checked locking against a Redisson RLock keyed per product id:
     *   1. Re-check L2 AFTER acquiring the lock (another thread may have already populated it
     *      while we were waiting).
     *   2. Only the lock holder queries the DB.
     *   3. Backfill L2 then L1 before releasing.
     *   4. If the lock can't be acquired within lockWaitSeconds (cluster under extreme load),
     *      fall back to a direct, unprotected DB read rather than failing the request outright -
     *      a bounded number of extra DB hits beats an unbounded request queue.
     */
    private Optional<Product> loadFromDbWithStampedeProtection(String productId) {
        String lockKey = "lock:product:" + productId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(
                    cacheProperties.getStampede().getLockWaitSeconds(),
                    cacheProperties.getStampede().getLockLeaseSeconds(),
                    TimeUnit.SECONDS);

            if (locked) {
                // double-check: someone may have populated L2 while we waited for the lock
                Product recheck = readFromL2(productId);
                if (recheck != null) {
                    log.debug("L2 populated by another thread while waiting for lock, id='{}'", productId);
                    cacheInBothLayers(productId, recheck);
                    return Optional.of(recheck);
                }
                return queryDbAndBackfill(productId);
            } else {
                log.warn("Could not acquire stampede lock for id='{}' within {}s - reading DB directly",
                        productId, cacheProperties.getStampede().getLockWaitSeconds());
                return queryDbAndBackfill(productId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for stampede lock, id='{}'", productId);
            return productRepository.findById(productId); // last-resort direct read, no caching
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Optional<Product> queryDbAndBackfill(String productId) {
        Optional<Product> fromDb = productRepository.findById(productId);
        if (fromDb.isPresent()) {
            cacheInBothLayers(productId, fromDb.get());
        } else {
            // Legitimate miss (bloom filter said "maybe", DB said "no") - cache negatively
            // with a short TTL so a repeated request for the same dead ID doesn't keep
            // re-hitting the DB during the window before the bloom filter is next rebuilt.
            Cache notFoundCache = redisCacheManager.getCache(NOT_FOUND_CACHE_NAME);
            if (notFoundCache != null) {
                notFoundCache.put(productId, NOT_FOUND_SENTINEL);
            }
            log.debug("DB MISS id='{}' - cached negative sentinel", productId);
        }
        return fromDb;
    }

    private void cacheInBothLayers(String productId, Product product) {
        Cache l2 = redisCacheManager.getCache(L2_CACHE_NAME);
        if (l2 != null) {
            l2.put(productId, product);
        }
        Cache l1 = l1CacheManager.getCache(L2_CACHE_NAME);
        if (l1 != null) {
            l1.put(productId, product);
        }
    }

    // ------------------------------------------------------------------
    // WRITE PATH
    // ------------------------------------------------------------------

    /**
     * Full backfill write: update DB, refresh both cache layers with the new value, then
     * publish so every OTHER instance drops its own (now stale) L1 copy.
     */
    public Product putProduct(Product product) {
        product.setLastUpdated(Instant.now());
        Product saved = productRepository.save(product);
        productIdBloomFilter.add(saved.getId()); // idempotent if already present
        cacheInBothLayers(saved.getId(), saved);
        clearNegativeCache(saved.getId());

        // We already updated our OWN L1 above (cacheInBothLayers put the fresh value in,
        // which is strictly better than evicting), so we just need every OTHER instance
        // to drop their now-stale L1 copy.
        invalidationPublisher.publishEvict(L2_CACHE_NAME, saved.getId());
        return saved;
    }

    /**
     * Evict-only write path (used when the caller doesn't have - or want to push - the new
     * value, e.g. after a delete).
     */
    public void evictProduct(String productId) {
        Cache l1 = l1CacheManager.getCache(L2_CACHE_NAME);
        if (l1 != null) {
            l1.evict(productId);
        }
        Cache l2 = redisCacheManager.getCache(L2_CACHE_NAME);
        if (l2 != null) {
            l2.evict(productId);
        }
        invalidationPublisher.publishEvict(L2_CACHE_NAME, productId);
    }

    private void clearNegativeCache(String productId) {
        Cache notFoundCache = redisCacheManager.getCache(NOT_FOUND_CACHE_NAME);
        if (notFoundCache != null) {
            notFoundCache.evict(productId);
        }
    }
}
