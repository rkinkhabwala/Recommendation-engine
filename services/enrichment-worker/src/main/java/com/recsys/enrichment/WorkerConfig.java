package com.recsys.enrichment;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.recsys.common.Topics;
import com.recsys.features.RedisConnections;
import com.recsys.features.RedisFeatureStore;
import com.recsys.openai.ChatClient;
import com.recsys.openai.EmbeddingClients;
import com.recsys.openai.OpenAiSettings;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
class WorkerConfig {

  @Bean
  OpenAiSettings openAiSettings(Environment env) {
    return Binder.get(env).bind("recs.openai", OpenAiSettings.class).get();
  }

  @Bean
  ChatClient chatClient(OpenAiSettings settings, MeterRegistry registry) {
    return EmbeddingClients.chat(
        settings, registry, EmbeddingClients.costMeter(settings, registry), MockLlm.handlers());
  }

  @Bean(destroyMethod = "shutdown")
  RedisClient redisClient() {
    return RedisClient.create();
  }

  /** Read-only use (item metadata, explanation cache checks); writes go through Kafka. */
  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, byte[]> redisConnection(
      RedisClient client, @Value("${recs.redis.uri}") String uri) {
    return RedisConnections.connect(client, uri, Duration.ofSeconds(2));
  }

  @Bean
  ItemEnricher itemEnricher(ChatClient llm, EnrichmentProperties props, MeterRegistry registry) {
    return new ItemEnricher(
        llm, new HttpCatalogApi(props.catalogUrl(), props.apiKey()), props, registry);
  }

  @Bean
  ExplanationGenerator explanationGenerator(
      ChatClient llm,
      StatefulRedisConnection<String, byte[]> redis,
      KafkaProperties kafka,
      EnrichmentProperties props,
      MeterRegistry registry) {
    Map<String, Object> config = new HashMap<>(kafka.buildProducerProperties());
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    KafkaTemplate<String, byte[]> features =
        new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    return new ExplanationGenerator(
        llm,
        new RedisFeatureStore(redis),
        (key, envelope) -> {
          try {
            features.send(Topics.FEATURES_ITEM, key, envelope).get(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            throw new IllegalStateException("Publishing explanation failed", e);
          }
        },
        props,
        Caffeine.newBuilder().maximumSize(500_000).expireAfterWrite(Duration.ofDays(1)).build(),
        registry,
        Clock.systemUTC());
  }

  /** Retryable failures (OpenAI outage, budget exhausted, catalog down) pause with backoff. */
  @Bean
  CommonErrorHandler errorHandler() {
    ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
    backOff.setMaxInterval(60_000);
    backOff.setMaxElapsedTime(Long.MAX_VALUE);
    return new DefaultErrorHandler(backOff);
  }
}
