package com.souptik.inventory.config;

import com.souptik.inventory.pubsub.RedisPubSubListener;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@RequiredArgsConstructor
public class PubSubConfig {

    @Value("${app.cache.pubsub.invalidation-channel:cache:invalidate:l1}")
    private String invalidationChannel;

    @Bean
    public ChannelTopic cacheInvalidationTopic() {
        return new ChannelTopic(invalidationChannel);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisPubSubListener listener,
            ChannelTopic cacheInvalidationTopic) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Small dedicated task executor sizing keeps subscriber processing off the
        // connection I/O thread without needing a full custom TaskExecutor bean here.
        container.addMessageListener(listener, cacheInvalidationTopic);
        return container;
    }
}
