package com.recsys.api.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.recsys.api.candidates.CandidateGenerator;
import com.recsys.api.candidates.CandidateService;
import com.recsys.api.candidates.FreshItemsGenerator;
import com.recsys.api.candidates.GuardedGenerator;
import com.recsys.api.candidates.ItemItemCfGenerator;
import com.recsys.api.candidates.NextItemGenerator;
import com.recsys.api.candidates.SemanticAnnGenerator;
import com.recsys.api.candidates.TrendingGenerator;
import com.recsys.api.core.RecommendationService;
import com.recsys.api.experiment.Experiments;
import com.recsys.api.fallback.PopularCache;
import com.recsys.api.hydration.Hydrator;
import com.recsys.api.logging.ServedLogger;
import com.recsys.api.ranking.RankerRegistry;
import com.recsys.api.rerank.DiversityReRanker;
import com.recsys.api.rerank.ExplorationReRanker;
import com.recsys.api.rerank.FamiliarityCapReRanker;
import com.recsys.api.rerank.FreshnessBoostReRanker;
import com.recsys.api.rerank.HardFilterReRanker;
import com.recsys.features.FeatureReader;
import com.recsys.features.RedisConnections;
import com.recsys.features.RedisFeatureStore;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.vector.QdrantVectorIndex;
import com.recsys.vector.VectorIndex;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration
class ServingConfig {

  @Bean(destroyMethod = "shutdown")
  RedisClient redisClient() {
    return RedisClient.create();
  }

  @Bean(destroyMethod = "close")
  StatefulRedisConnection<String, byte[]> redisConnection(
      RedisClient client, @Value("${recs.redis.uri}") String uri) {
    return RedisConnections.connect(client, uri, Duration.ofMillis(200));
  }

  @Bean
  FeatureReader featureReader(StatefulRedisConnection<String, byte[]> connection) {
    return new RedisFeatureStore(connection);
  }

  @Bean(destroyMethod = "close")
  QdrantClient qdrantClient(
      @Value("${recs.qdrant.host}") String host, @Value("${recs.qdrant.grpc-port}") int port) {
    return new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build());
  }

  @Bean
  VectorIndex vectorIndex(QdrantClient client) {
    return new QdrantVectorIndex(client, Duration.ofSeconds(2));
  }

  @Bean
  CircuitBreaker redisBreaker() {
    return breaker("redis");
  }

  @Bean
  CircuitBreaker qdrantBreaker() {
    return breaker("qdrant");
  }

  /**
   * Opens only on dependency failures (connection refused, store errors, client-side command
   * timeouts), not when a request's own tight stage budget expires: a 10 ms budget miss is a
   * latency policy, not an outage, and must not push healthy traffic into fallback.
   */
  private static CircuitBreaker breaker(String name) {
    return CircuitBreaker.of(
        name,
        CircuitBreakerConfig.custom()
            .slidingWindowSize(100)
            .minimumNumberOfCalls(50)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(5))
            .permittedNumberOfCallsInHalfOpenState(5)
            .recordException(ServingConfig::isDependencyFailure)
            .build());
  }

  static boolean isDependencyFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof java.util.concurrent.TimeoutException
          || t instanceof java.util.concurrent.CancellationException
          || t instanceof InterruptedException) {
        return false; // our own deadline, not the store's fault
      }
    }
    return true;
  }

  @Bean(destroyMethod = "close")
  ExecutorService servingExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  CandidateService candidateService(
      VectorIndex index,
      FeatureReader features,
      ApiProperties props,
      CircuitBreaker redisBreaker,
      CircuitBreaker qdrantBreaker,
      ExecutorService servingExecutor,
      MeterRegistry registry) {
    List<CandidateGenerator> generators =
        List.of(
            new GuardedGenerator(new SemanticAnnGenerator(index, props), qdrantBreaker),
            new GuardedGenerator(new FreshItemsGenerator(index, props), qdrantBreaker),
            new GuardedGenerator(new ItemItemCfGenerator(features, props), redisBreaker),
            new GuardedGenerator(new NextItemGenerator(features, props), redisBreaker),
            new GuardedGenerator(new TrendingGenerator(features, props), redisBreaker));
    return new CandidateService(generators, servingExecutor, registry);
  }

  @Bean
  Hydrator hydrator(
      FeatureReader features, VectorIndex index, ApiProperties props, MeterRegistry registry) {
    Cache<String, ItemMeta> meta =
        Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .recordStats()
            .build();
    Cache<String, ItemStats> stats =
        Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterWrite(Duration.ofSeconds(10))
            .recordStats()
            .build();
    CaffeineCacheMetrics.monitor(registry, meta, "item_meta");
    CaffeineCacheMetrics.monitor(registry, stats, "item_stats");
    return new Hydrator(features, index, props.indexAlias(), meta, stats);
  }

  @Bean
  PopularCache popularCache(FeatureReader features) {
    return new PopularCache(features, "/fallback/static-popular-%s.json");
  }

  @Bean(destroyMethod = "close")
  ServedLogger servedLogger(KafkaTemplate<String, SpecificRecord> kafka, MeterRegistry registry) {
    return new ServedLogger(kafka, 10_000, registry);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  Experiments experiments(ApiProperties props) {
    return new Experiments(props.experiments());
  }

  @Bean
  RankerRegistry rankerRegistry(ApiProperties props, MeterRegistry registry) {
    var r = new RankerRegistry(props.ranking().modelDir(), registry);
    r.reload();
    return r;
  }

  @Bean
  RecommendationService recommendationService(
      FeatureReader features,
      CandidateService candidates,
      Hydrator hydrator,
      RankerRegistry rankers,
      PopularCache popular,
      ServedLogger servedLogger,
      Experiments experiments,
      ApiProperties props,
      CircuitBreaker redisBreaker,
      MeterRegistry registry,
      Clock clock) {
    return new RecommendationService(
        features,
        candidates,
        hydrator,
        rankers,
        List.of(
            new HardFilterReRanker(),
            new FreshnessBoostReRanker(),
            new FamiliarityCapReRanker(),
            new DiversityReRanker(),
            new ExplorationReRanker()),
        popular,
        servedLogger,
        experiments,
        props,
        redisBreaker,
        registry,
        clock);
  }
}
