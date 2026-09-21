package com.publicissapient.inventory.pubsub;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Message broadcast on the Redis Pub/Sub invalidation channel whenever an instance
 * writes/evicts a key in L2 (Redis), so that every OTHER instance's L1 (Caffeine)
 * cache drops its now-stale local copy.
 *
 * originInstanceId lets the publishing instance skip self-invalidation (it already
 * evicted its own L1 entry synchronously before publishing).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheInvalidationMessage implements Serializable {

    /** Logical Spring cache name, e.g. "product-details" */
    private String cacheName;

    /** The cache key (typically the product id) to evict from every instance's L1 */
    private String key;

    /** Distinguishes EVICT (single key) from CLEAR (whole cache) */
    private Type type;

    private String originInstanceId;
    private Instant timestamp;

    public enum Type { EVICT, CLEAR }

    public static CacheInvalidationMessage evict(String cacheName, String key, String originInstanceId) {
        return new CacheInvalidationMessage(cacheName, key, Type.EVICT, originInstanceId, Instant.now());
    }

    public static CacheInvalidationMessage clear(String cacheName, String originInstanceId) {
        return new CacheInvalidationMessage(cacheName, null, Type.CLEAR, originInstanceId, Instant.now());
    }
}
