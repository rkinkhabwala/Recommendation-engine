package com.recsys.stream.config;

import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.stream.topology.OnlineMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/** CTR / completion / skip per variant = outcomes_total / served_items_total in Prometheus. */
final class MicrometerOnlineMetrics implements OnlineMetrics {
  private final MeterRegistry registry;

  MicrometerOnlineMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void served(RecommendationServed served) {
    registry
        .counter(
            "recs_online_served_items_total",
            "variant",
            served.getVariantId(),
            "fallback",
            served.getFallbackLevel())
        .increment(served.getItems().size());
  }

  @Override
  public void outcome(RecommendationAttributed a) {
    registry
        .counter(
            "recs_online_outcomes_total",
            "variant",
            a.getVariantId(),
            "outcome",
            a.getOutcome().name(),
            "explore",
            Boolean.toString(a.getExplore()))
        .increment();
  }
}
