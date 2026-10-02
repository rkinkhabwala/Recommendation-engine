package com.recsys.embedding;

import com.recsys.common.IndexVersion;
import com.recsys.common.Topics;
import com.recsys.events.v1.CatalogItem;
import com.recsys.openai.EmbeddingClient;
import com.recsys.openai.JobPriority;
import com.recsys.openai.OpenAiBatchClient;
import com.recsys.openai.OpenAiSettings;
import com.recsys.vector.VectorIndex;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code --spring.profiles.active=backfill --recs.backfill.target-index=items_te3s_1536_v1}: reads
 * the compacted catalog topic end to end and runs {@link Backfiller}; then exits. Uses the OpenAI
 * Batch API in openai mode and the direct (mock) client otherwise.
 */
@Component
@ConditionalOnProperty(name = "recs.backfill.enabled", havingValue = "true")
class BackfillRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(BackfillRunner.class);

  private final KafkaProperties kafka;
  private final VectorIndex index;
  private final EmbeddingClient client;
  private final OpenAiSettings settings;
  private final KafkaTemplate<String, SpecificRecord> template;
  private final WorkerProperties props;
  private final ApplicationContext context;

  @Value("${recs.backfill.target-index}")
  String targetIndex;

  @Value("${recs.backfill.switch-alias:false}")
  boolean switchAlias;

  @Value("${recs.backfill.chunk-size:5000}")
  int chunkSize;

  @Value("${recs.backfill.poll-interval:30s}")
  Duration pollInterval;

  BackfillRunner(
      KafkaProperties kafka,
      VectorIndex index,
      EmbeddingClient client,
      OpenAiSettings settings,
      KafkaTemplate<String, SpecificRecord> template,
      WorkerProperties props,
      ApplicationContext context) {
    this.kafka = kafka;
    this.index = index;
    this.client = client;
    this.settings = settings;
    this.template = template;
    this.props = props;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    IndexVersion target = IndexVersion.parse(targetIndex).requireDims(client.dimensions());
    createTopic("catalog.embeddings." + target.name());
    Map<String, CatalogItem> catalog = readCatalog();
    log.info("Backfill: {} catalog records read", catalog.size());
    var backfiller =
        new Backfiller(
            index,
            settings.mock() ? direct() : batch(),
            (topic, records) ->
                records.stream()
                    .map(r -> template.send(topic, r.getItemId(), r))
                    .toList()
                    .forEach(java.util.concurrent.CompletableFuture::join),
            Clock.systemUTC());
    var result =
        backfiller.run(
            catalog,
            target.name(),
            target.dims(),
            client.model(),
            props.templateVersion(),
            props.alias(),
            switchAlias,
            chunkSize);
    log.info("Backfill finished: {}", result);
    System.exit(SpringApplication.exit(context, () -> result.missing() == 0 ? 0 : 1));
  }

  private Backfiller.ChunkEmbedder direct() {
    return texts -> {
      List<String> ids = List.copyOf(texts.keySet());
      Map<String, float[]> out = new HashMap<>();
      for (int i = 0; i < ids.size(); i += props.embedBatchSize()) {
        List<String> part = ids.subList(i, Math.min(ids.size(), i + props.embedBatchSize()));
        var res =
            client.embed(
                part.stream().map(texts::get).toList(), "backfill", JobPriority.NON_ESSENTIAL);
        for (int j = 0; j < part.size(); j++) {
          out.put(part.get(j), res.vectors().get(j));
        }
      }
      return out;
    };
  }

  private Backfiller.ChunkEmbedder batch() {
    var batch =
        new OpenAiBatchClient(
            settings,
            com.recsys.openai.EmbeddingClients.costMeter(
                settings, new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    return texts -> {
      String id =
          batch.submitEmbeddings(
              texts.entrySet().stream()
                  .map(e -> new OpenAiBatchClient.BatchInput(e.getKey(), e.getValue()))
                  .toList(),
              JobPriority.NON_ESSENTIAL);
      log.info("Submitted batch {} ({} inputs)", id, texts.size());
      while (true) {
        var status = batch.status(id);
        if (status.done()) {
          if (!"completed".equals(status.status()) || status.outputFileId() == null) {
            throw new IllegalStateException(
                "Batch " + id + " ended with status " + status.status());
          }
          return batch.results(status.outputFileId(), "backfill");
        }
        try {
          TimeUnit.MILLISECONDS.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    };
  }

  private Map<String, CatalogItem> readCatalog() {
    Properties p = new Properties();
    p.putAll(kafka.buildConsumerProperties());
    p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    p.remove(ConsumerConfig.GROUP_ID_CONFIG);
    Map<String, CatalogItem> latest = new HashMap<>();
    try (var consumer = new KafkaConsumer<String, CatalogItem>(p)) {
      List<TopicPartition> parts =
          consumer.partitionsFor(Topics.CATALOG_ITEMS).stream()
              .map(i -> new TopicPartition(i.topic(), i.partition()))
              .toList();
      consumer.assign(parts);
      consumer.seekToBeginning(parts);
      Map<TopicPartition, Long> end = consumer.endOffsets(parts);
      while (parts.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
        for (var r : consumer.poll(Duration.ofSeconds(1))) {
          latest.put(r.key(), r.value()); // null = deleted (dropped by the backfiller)
        }
      }
    }
    return latest;
  }

  private void createTopic(String name) throws Exception {
    try (var admin = AdminClient.create(kafka.buildAdminProperties(null))) {
      if (admin.listTopics().names().get().contains(name)) {
        return;
      }
      int partitions =
          admin
              .describeTopics(List.of(Topics.CATALOG_ITEMS))
              .allTopicNames()
              .get()
              .get(Topics.CATALOG_ITEMS)
              .partitions()
              .size();
      admin
          .createTopics(
              List.of(
                  new NewTopic(name, partitions, (short) 1)
                      .configs(
                          Map.of(
                              TopicConfig.CLEANUP_POLICY_CONFIG,
                              TopicConfig.CLEANUP_POLICY_COMPACT))))
          .all()
          .get();
      log.info("Created topic {}", name);
    }
  }
}
