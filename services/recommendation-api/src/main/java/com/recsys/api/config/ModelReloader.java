package com.recsys.api.config;

import com.recsys.api.ranking.RankerRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the model registry and hot-swaps new ranker versions (no restart, no request impact). */
@Component
class ModelReloader {
  private final RankerRegistry registry;

  ModelReloader(RankerRegistry registry) {
    this.registry = registry;
  }

  @Scheduled(
      fixedDelayString = "${recs.api.ranking.reload-interval:30s}",
      initialDelayString = "30s")
  void reload() {
    registry.reload();
  }
}
