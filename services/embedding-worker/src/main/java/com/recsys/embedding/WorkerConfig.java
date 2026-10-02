package com.recsys.embedding;

import com.recsys.common.IndexVersion;
import com.recsys.common.Topics;
import com.recsys.events.v1.ItemEmbedding;
import com.recsys.openai.EmbeddingClient;
import com.recsys.openai.EmbeddingClients;
import com.recsys.openai.OpenAiSettings;
import com.recsys.vector.QdrantVectorIndex;
import com.recsys.vector.VectorIndex;
import io.micrometer.core.instrument.MeterRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
  EmbeddingClient embeddingClient(OpenAiSettings settings, MeterRegistry registry) {
    return EmbeddingClients.create(settings, registry);
  }

  @Bean(destroyMethod = "close")
  QdrantClient qdrantClient(WorkerProperties props) {
    return new QdrantClient(
        QdrantGrpcClient.newBuilder(props.qdrantHost(), props.qdrantPort(), false).build());
  }

  @Bean
  VectorIndex vectorIndex(QdrantClient client, WorkerProperties props, EmbeddingClient embeddings) {
    var index = new QdrantVectorIndex(client, props.qdrantTimeout());
    IndexVersion version =
        IndexVersion.parse(props.indexVersion()).requireDims(embeddings.dimensions());
    index.ensureCollection(version.name(), version.dims(), props.alias());
    return index;
  }

  /** Not a bean: a second KafkaTemplate bean would disable Boot's auto-configured Avro template. */
  private static KafkaTemplate<String, String> dlqTemplate(KafkaProperties kafka) {
    Map<String, Object> config = new HashMap<>(kafka.buildProducerProperties());
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
  }

  @Bean
  CatalogEmbedder catalogEmbedder(
      EmbeddingClient client,
      VectorIndex index,
      KafkaTemplate<String, SpecificRecord> avroTemplate,
      KafkaProperties kafkaProperties,
      WorkerProperties props,
      MeterRegistry registry) {
    KafkaTemplate<String, String> dlqTemplate = dlqTemplate(kafkaProperties);
    var sink =
        new CatalogEmbedder.EmbeddingSink() {
          @Override
          public void publish(List<ItemEmbedding> embeddings) {
            var futures =
                embeddings.stream()
                    .map(e -> avroTemplate.send(Topics.CATALOG_EMBEDDINGS, e.getItemId(), e))
                    .toList();
            for (var f : futures) {
              try {
                f.get(30, TimeUnit.SECONDS);
              } catch (Exception ex) {
                throw new IllegalStateException("Publishing embeddings failed", ex);
              }
            }
          }

          @Override
          public void tombstone(String itemId) {
            avroTemplate.send(Topics.CATALOG_EMBEDDINGS, itemId, null).join();
          }

          @Override
          public void deadLetter(String itemId, String reason) {
            String json =
                "{\"itemId\":\"%s\",\"job\":\"catalog\",\"reason\":%s,\"ts\":%d}"
                    .formatted(itemId, quote(reason), System.currentTimeMillis());
            dlqTemplate.send(Topics.EMBEDDING_DLQ, itemId, json).join();
          }
        };
    return new CatalogEmbedder(client, index, sink, props, registry, Clock.systemUTC());
  }

  @Bean
  OnboardingSeeder onboardingSeeder(
      EmbeddingClient client, VectorIndex index, KafkaProperties kafka, WorkerProperties props) {
    Map<String, Object> config = new HashMap<>(kafka.buildProducerProperties());
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    KafkaTemplate<String, byte[]> features =
        new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    return new OnboardingSeeder(
        client,
        index,
        (key, envelope) ->
            features.send(Topics.FEATURES_USER, key, envelope).get(10, TimeUnit.SECONDS),
        props);
  }

  /**
   * Retryable failures (OpenAI 429/5xx/open circuit, Qdrant/Kafka down) retry the batch with capped
   * backoff indefinitely; the consumer is paused between attempts and lag builds up.
   */
  @Bean
  CommonErrorHandler embeddingErrorHandler() {
    ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
    backOff.setMaxInterval(60_000);
    backOff.setMaxElapsedTime(Long.MAX_VALUE);
    return new DefaultErrorHandler(backOff);
  }

  private static String quote(String s) {
    return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
  }
}
