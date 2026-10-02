package com.recsys.stream.config;

import com.recsys.features.RedisConnections;
import com.recsys.features.RedisFeatureStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
class WriterConfig {

  @Bean(destroyMethod = "shutdown")
  RedisClient redisClient() {
    return RedisClient.create();
  }

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, byte[]> redisConnection(
      RedisClient client, @Value("${recs.redis.uri}") String uri) {
    return RedisConnections.connect(client, uri, Duration.ofSeconds(5));
  }

  @Bean
  RedisFeatureStore featureStore(StatefulRedisConnection<String, byte[]> connection) {
    return new RedisFeatureStore(connection);
  }

  /**
   * Retry forever with capped exponential backoff: Redis outages delay features, never drop them.
   */
  @Bean
  CommonErrorHandler featureWriterErrorHandler() {
    ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
    backOff.setMaxInterval(30_000);
    backOff.setMaxElapsedTime(Long.MAX_VALUE);
    return new DefaultErrorHandler(backOff);
  }
}
