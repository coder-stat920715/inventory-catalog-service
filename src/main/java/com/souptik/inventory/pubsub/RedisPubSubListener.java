package com.souptik.inventory.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listens on the shared invalidation channel and evicts the matching entry from THIS
 * instance's L1 Caffeine cache. Runs on every instance in the fleet.
 *
 * This is the mechanism that keeps L1 caches (which are, by definition, per-JVM and
 * otherwise invisible to every other instance) from serving stale data after another
 * instance updates the source of truth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisPubSubListener implements MessageListener {

    private final CacheManager l1CacheManager;
    private final ObjectMapper redisObjectMapper;
    private final CacheInvalidationPublisher publisher;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            CacheInvalidationMessage event = redisObjectMapper.readValue(body, CacheInvalidationMessage.class);

            // Skip messages this very instance published - it already evicted locally,
            // inline, at write time, before the publish call.
            if (publisher.instanceId().equals(event.getOriginInstanceId())) {
                return;
            }

            org.springframework.cache.Cache l1Cache = l1CacheManager.getCache(event.getCacheName());
            if (l1Cache == null) {
                return; // this cache name isn't mirrored in L1 on this instance - nothing to do
            }

            switch (event.getType()) {
                case EVICT -> {
                    l1Cache.evict(event.getKey());
                    log.debug("L1 evicted cache='{}' key='{}' (origin={})",
                            event.getCacheName(), event.getKey(), event.getOriginInstanceId());
                }
                case CLEAR -> {
                    l1Cache.clear();
                    log.debug("L1 cleared cache='{}' (origin={})", event.getCacheName(), event.getOriginInstanceId());
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to process cache invalidation message: {}", ex.toString());
        }
    }
}
