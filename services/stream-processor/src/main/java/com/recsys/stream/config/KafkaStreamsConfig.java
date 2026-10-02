package com.recsys.stream.config;

import com.recsys.stream.serde.StreamSerdes;
import com.recsys.stream.signals.SignalWeigher;
import com.recsys.stream.topology.RecsTopology;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.kafka.KafkaStreamsMetrics;
import java.time.Duration;
import java.util.Properties;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class KafkaStreamsConfig {
  private static final Logger log = LoggerFactory.getLogger(KafkaStreamsConfig.class);

  @Bean
  Topology recsTopology(StreamProperties props, MeterRegistry registry) {
    return RecsTopology.build(
        props.topology(),
        new SignalWeigher(props.songSignals()),
        new StreamSerdes(props.schemaRegistryUrl()),
        new MicrometerOnlineMetrics(registry));
  }

  @Bean
  KafkaStreams kafkaStreams(
      Topology topology,
      StreamProperties props,
      MeterRegistry registry,
      @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
    Properties p = new Properties();
    p.put(StreamsConfig.APPLICATION_ID_CONFIG, props.applicationId());
    p.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    p.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
    p.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, props.commitInterval().toMillis());
    p.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, props.standbyReplicas());
    p.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, props.replicationFactor());
    p.put(StreamsConfig.STATE_DIR_CONFIG, props.stateDir());
    p.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 10 * 1024 * 1024L);
    p.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, BoundedMemoryRocksDbConfig.class);
    p.put(
        StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
        LogAndContinueExceptionHandler.class);
    // Changelogs of user-keyed stores must actually compact so deletion tombstones purge data.
    p.put(
        StreamsConfig.topicPrefix(TopicConfig.MAX_COMPACTION_LAG_MS_CONFIG),
        Long.toString(props.maxCompactionLag().toMillis()));
    p.put(
        StreamsConfig.topicPrefix(TopicConfig.DELETE_RETENTION_MS_CONFIG),
        Long.toString(Duration.ofDays(1).toMillis()));
    String instance = System.getenv("HOSTNAME");
    if (instance != null && !instance.isBlank()) {
      p.put(
          StreamsConfig.consumerPrefix("group.instance.id"),
          props.applicationId() + "-" + instance);
    }
    KafkaStreams streams = new KafkaStreams(topology, p);
    streams.setUncaughtExceptionHandler(
        e -> {
          log.error("Stream thread failed; replacing it", e);
          return StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.REPLACE_THREAD;
        });
    new KafkaStreamsMetrics(streams).bindTo(registry);
    return streams;
  }

  @Bean
  SmartLifecycle kafkaStreamsLifecycle(KafkaStreams streams) {
    return new SmartLifecycle() {
      private volatile boolean running;

      @Override
      public void start() {
        log.info("Starting Kafka Streams");
        streams.start();
        running = true;
      }

      @Override
      public void stop() {
        streams.close(Duration.ofSeconds(30));
        running = false;
      }

      @Override
      public boolean isRunning() {
        return running;
      }
    };
  }

  @Bean
  HealthIndicator kafkaStreamsHealth(KafkaStreams streams) {
    return () -> {
      KafkaStreams.State state = streams.state();
      boolean up =
          state == KafkaStreams.State.RUNNING
              || state == KafkaStreams.State.REBALANCING
              || state == KafkaStreams.State.CREATED;
      return (up ? Health.up() : Health.down()).withDetail("state", state.name()).build();
    };
  }
}
