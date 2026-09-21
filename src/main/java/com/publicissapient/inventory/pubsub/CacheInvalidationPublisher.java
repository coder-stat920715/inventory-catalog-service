package com.publicissapient.inventory.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes cache-invalidation events on a Redis Pub/Sub channel every time this instance
 * writes to or evicts from L2 (Redis). Every instance in the fleet - including this one's
 * siblings, NOT this instance itself (we evict our own L1 entry synchronously, inline,
 * before publishing) - reacts by dropping the corresponding L1 (Caffeine) entry.
 *
 * Pub/Sub is fire-and-forget / at-most-once by design (no persistence, no replay, no ack).
 * That's an intentional trade-off here: L1 entries are short-lived (default 30s TTL, see
 * app.cache.l1.expire-after-write-seconds), so a missed invalidation message is
 * self-healing within one L1 TTL window at worst. If you needed guaranteed delivery you'd
 * reach for Redis Streams (consumer groups + ack) instead of Pub/Sub - noted in the
 * Interview Defense Strategy doc.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheInvalidationPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper redisObjectMapper;

    @Value("${app.cache.pubsub.invalidation-channel:cache:invalidate:l1}")
    private String channel;

    /** Stable per-JVM identifier so listeners can ignore messages that originated locally. */
    private final String instanceId = java.util.UUID.randomUUID().toString();

    public String instanceId() {
        return instanceId;
    }

    public void publishEvict(String cacheName, String key) {
        publish(CacheInvalidationMessage.evict(cacheName, key, instanceId));
    }

    public void publishClear(String cacheName) {
        publish(CacheInvalidationMessage.clear(cacheName, instanceId));
    }

    private void publish(CacheInvalidationMessage message) {
        try {
            String payload = redisObjectMapper.writeValueAsString(message);
            stringRedisTemplate.convertAndSend(channel, payload);
            log.debug("Published invalidation event: {}", message);
        } catch (Exception ex) {
            // Never let a pub/sub failure break the write path - L1 entries still expire
            // naturally via their own short TTL, so this is a soft failure.
            log.warn("Failed to publish cache invalidation event for cache='{}' key='{}': {}",
                    message.getCacheName(), message.getKey(), ex.toString());
        }
    }
}
