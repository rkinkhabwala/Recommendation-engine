package com.recsys.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Re-publishes committed versions that never reached Kafka/Redis (dual-write repair). */
@Component
public class CatalogReconciler {
  private static final Logger log = LoggerFactory.getLogger(CatalogReconciler.class);
  private final CatalogRepository repository;
  private final CatalogPublisher publisher;

  public CatalogReconciler(CatalogRepository repository, CatalogPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  @Scheduled(
      fixedDelayString = "${recs.catalog.reconcile-interval:30s}",
      initialDelayString = "10s")
  public void reconcile() {
    // Drain the whole backlog each cycle (500 rows per query) until caught up or a dependency
    // fails.
    while (true) {
      var pending = repository.unpublished(500);
      if (pending.isEmpty()) {
        return;
      }
      log.info("Reconciling {} unpublished catalog versions", pending.size());
      for (var p : pending) {
        try {
          if (p.deleted()) {
            publisher.publishDeletion(p.item().itemId(), p.item().seq());
          } else {
            publisher.publish(p.item());
          }
        } catch (Exception e) {
          log.warn("Reconcile failed for {}; will retry: {}", p.item().itemId(), e.toString());
          return; // dependency likely down; retry next cycle
        }
      }
    }
  }
}
