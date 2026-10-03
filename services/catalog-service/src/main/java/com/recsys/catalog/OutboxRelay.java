package com.recsys.catalog;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the transactional outbox to Kafka. Each tick claims a batch ({@code FOR UPDATE SKIP
 * LOCKED}), collapses repeated changes of one item to its latest committed version, publishes them,
 * waits for broker acks, then deletes the rows — all in one DB transaction, so a crash before the
 * delete republishes (at-least-once; consumers are idempotent via {@code seq}).
 */
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  public interface Sink {
    CompletableFuture<?> publish(CatalogItemDto item);

    CompletableFuture<?> tombstone(String itemId);
  }

  private final CatalogRepository repository;
  private final Sink sink;
  private final TransactionTemplate tx;
  private final int batchSize;
  private volatile double pending;
  private volatile double oldestSeconds;

  public OutboxRelay(
      CatalogRepository repository,
      Sink sink,
      TransactionTemplate tx,
      int batchSize,
      MeterRegistry metrics) {
    this.repository = repository;
    this.sink = sink;
    this.tx = tx;
    this.batchSize = batchSize;
    if (metrics != null) {
      Gauge.builder("recs_catalog_outbox_pending", () -> pending).register(metrics);
      Gauge.builder("recs_catalog_outbox_oldest_seconds", () -> oldestSeconds).register(metrics);
    }
  }

  @Scheduled(fixedDelayString = "${recs.catalog.outbox-interval:200ms}")
  public void tick() {
    try {
      while (relayBatch() == batchSize) {
        // keep draining while there is a backlog
      }
      double[] stats = repository.outboxStats();
      pending = stats[0];
      oldestSeconds = stats[1];
    } catch (RuntimeException e) {
      log.warn("Outbox relay failed; rows stay queued and are retried: {}", e.toString());
    }
  }

  /**
   * @return number of outbox rows relayed
   */
  public int relayBatch() {
    Integer n =
        tx.execute(
            status -> {
              var entries = repository.lockOutbox(batchSize);
              if (entries.isEmpty()) {
                return 0;
              }
              Map<String, List<Long>> idsByItem = new LinkedHashMap<>();
              entries.forEach(
                  e -> idsByItem.computeIfAbsent(e.itemId(), k -> new ArrayList<>()).add(e.id()));
              List<CompletableFuture<?>> sends = new ArrayList<>();
              for (var current : repository.current(idsByItem.keySet())) {
                sends.add(
                    current.deleted()
                        ? sink.tombstone(current.item().itemId())
                        : sink.publish(current.item()));
              }
              try {
                CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new IllegalStateException("Kafka publish failed", e); // rollback: rows stay
              }
              repository.deleteOutbox(
                  entries.stream().map(CatalogRepository.OutboxEntry::id).toList());
              return entries.size();
            });
    return n == null ? 0 : n;
  }
}
