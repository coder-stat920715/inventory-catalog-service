package com.souptik.inventory.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson is used ALONGSIDE Lettuce (via spring-data-redis), not instead of it.
 *
 *  - Lettuce/RedisTemplate: general-purpose data access, Spring Cache abstraction, Pub/Sub.
 *  - Redisson: higher-level distributed primitives that are painful to hand-roll correctly
 *    on top of raw commands - RLock (Redlock-style distributed mutex with a watchdog that
 *    auto-extends the lease while the holder is still alive), RBloomFilter, RScoredSortedSet.
 *
 * Running two clients against the same Redis instance is a completely standard, supported
 * pattern and is what most production Spring Boot + Redis stacks that need locking actually do.
 */
@Configuration
public class RedissonConfig {

    @Value("${redisson.address:redis://localhost:6379}")
    private String address;

    @Value("${redisson.connection-pool-size:24}")
    private int connectionPoolSize;

    @Value("${redisson.connection-minimum-idle-size:6}")
    private int connectionMinimumIdleSize;

    @Value("${redisson.lock-watchdog-timeout:30000}")
    private long lockWatchdogTimeout;

    @Value("${redisson.retry-attempts:3}")
    private int retryAttempts;

    @Value("${redisson.retry-interval:1500}")
    private int retryInterval;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setLockWatchdogTimeout(lockWatchdogTimeout);
        config.useSingleServer()
                .setAddress(address)
                .setConnectionPoolSize(connectionPoolSize)
                .setConnectionMinimumIdleSize(connectionMinimumIdleSize)
                .setRetryAttempts(retryAttempts)
                .setRetryInterval(retryInterval)
                .setTimeout(2000)
                .setConnectTimeout(1500);
        // NOTE: for production HA, replace useSingleServer() with useSentinelServers()
        // or useClusterServers() - see the "Interview Defense Strategy" doc, section 4.
        return Redisson.create(config);
    }
}
