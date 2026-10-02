package com.recsys.stream.topology;

import com.recsys.events.v1.RecommendationAttributed;
import com.recsys.events.v1.RecommendationServed;

/** Online evaluation counters (served items and outcomes per variant). */
public interface OnlineMetrics {
  OnlineMetrics NOOP =
      new OnlineMetrics() {
        @Override
        public void served(RecommendationServed served) {}

        @Override
        public void outcome(RecommendationAttributed attributed) {}
      };

  void served(RecommendationServed served);

  void outcome(RecommendationAttributed attributed);
}
