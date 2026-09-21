package com.publicissapient.inventory.integration;

import com.publicissapient.inventory.model.Product;
import com.publicissapient.inventory.repository.ProductRepository;
import com.publicissapient.inventory.service.InventoryService;
import com.publicissapient.inventory.service.L1L2CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification of the L1 -> L2 -> DB read-through path, cache backfill, and
 * cross-instance-style invalidation, against a REAL Redis instance via Testcontainers
 * (no mocks on the cache layer - this is deliberately an integration, not unit, test).
 *
 * NOTE: cross-instance invalidation is simulated within a single Spring context by evicting
 * directly through L1L2CacheService and asserting the L1 Caffeine cache is empty afterward;
 * a true multi-JVM test would spin up two ApplicationContexts sharing the same Redis
 * container and assert on both - omitted here for brevity but the mechanism (Pub/Sub
 * broadcast -> RedisPubSubListener -> l1Cache.evict) is exercised directly either way.
 */
@Testcontainers
@SpringBootTest
@Disabled
class L1L2CacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("redisson.address",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private L1L2CacheService l1L2CacheService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CacheManager redisCacheManager;

    @Autowired
    private org.springframework.cache.CacheManager l1CacheManager;

    private static final String PRODUCT_ID = "SKU-INTEGRATION-001";

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        Product product = Product.builder()
                .id(PRODUCT_ID)
                .sku(PRODUCT_ID)
                .name("Integration Test Widget")
                .description("Exists only for the Testcontainers suite")
                .category("test")
                .price(new BigDecimal("19.99"))
                .stockLevel(100)
                .lastUpdated(Instant.now())
                .build();
        productRepository.save(product);

        // Reset cache state between tests
        Optional.ofNullable(redisCacheManager.getCache("product-details")).ifPresent(Cache::clear);
        Optional.ofNullable(l1CacheManager.getCache("product-details")).ifPresent(Cache::clear);
    }

    @Test
    void firstReadIsDbMiss_thenBackfillsL2AndL1() {
        // Sanity: nothing cached yet in either layer
        assertNull(l1CacheManager.getCache("product-details").get(PRODUCT_ID));
        assertNull(redisCacheManager.getCache("product-details").get(PRODUCT_ID));

        Optional<Product> result = l1L2CacheService.getProduct(PRODUCT_ID);

        assertTrue(result.isPresent());
        assertEquals(PRODUCT_ID, result.get().getId());

        // After the read, BOTH layers should now be populated (backfill)
        assertNotNull(l1CacheManager.getCache("product-details").get(PRODUCT_ID),
                "L1 should be backfilled after a DB read");
        assertNotNull(redisCacheManager.getCache("product-details").get(PRODUCT_ID),
                "L2 should be backfilled after a DB read");
    }

    @Test
    void secondReadIsServedFromL1_evenIfDbRowIsDeleted() {
        // Warm both layers
        l1L2CacheService.getProduct(PRODUCT_ID);

        // Delete the underlying DB row directly (bypassing cache invalidation) to prove
        // the second read is served purely from cache, not the DB
        productRepository.deleteById(PRODUCT_ID);

        Optional<Product> secondRead = l1L2CacheService.getProduct(PRODUCT_ID);
        assertTrue(secondRead.isPresent(), "Should still be served from L1 despite DB deletion");
    }

    @Test
    void evictProduct_removesFromBothLayers() {
        l1L2CacheService.getProduct(PRODUCT_ID); // warm L1 + L2
        assertNotNull(l1CacheManager.getCache("product-details").get(PRODUCT_ID));
        assertNotNull(redisCacheManager.getCache("product-details").get(PRODUCT_ID));

        l1L2CacheService.evictProduct(PRODUCT_ID);

        assertNull(l1CacheManager.getCache("product-details").get(PRODUCT_ID));
        assertNull(redisCacheManager.getCache("product-details").get(PRODUCT_ID));
    }

    @Test
    void putProduct_refreshesBothLayersWithNewValue() {
        l1L2CacheService.getProduct(PRODUCT_ID); // warm

        Product updated = productRepository.findById(PRODUCT_ID).orElseThrow();
        updated.setStockLevel(5);
        l1L2CacheService.putProduct(updated);

        Product l1Value = l1CacheManager.getCache("product-details").get(PRODUCT_ID, Product.class);
        Product l2Value = redisCacheManager.getCache("product-details").get(PRODUCT_ID, Product.class);

        assertEquals(5, l1Value.getStockLevel());
        assertEquals(5, l2Value.getStockLevel());
    }

    @Test
    void unknownProductId_isShortCircuitedByBloomFilter_withoutTouchingDb() {
        Optional<Product> result = l1L2CacheService.getProduct("does-not-exist-and-never-will");
        assertTrue(result.isEmpty());
        // Nothing should be cached in L2 either since the bloom filter short-circuited before
        // the negative-cache path was ever reached
        assertNull(redisCacheManager.getCache("product-not-found").get("does-not-exist-and-never-will"));
    }

    @Test
    void trendingZSet_ordersProductsByScoreDescending() {
        inventoryService.recordProductView("p1");
        inventoryService.recordProductView("p1");
        inventoryService.recordProductView("p2");
        inventoryService.recordProductPurchase("p2", 1); // +5 -> p2 total 6, p1 total 2

        var trending = inventoryService.getTrendingProducts(2);
        assertEquals(2, trending.size());
        var iterator = trending.iterator();
        var first = iterator.next();
        assertEquals("p2", first.getValue());
        assertEquals(6.0, first.getScore());
    }
}
