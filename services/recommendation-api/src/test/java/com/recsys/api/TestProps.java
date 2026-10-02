package com.recsys.api;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.ranking.RankerWeights;
import java.time.Duration;
import java.util.List;

final class TestProps {
  static final String INDEX = "items_test_8_v1";

  static ApiProperties create() {
    return new ApiProperties(
        "items_current",
        INDEX,
        List.of("song"),
        Duration.ofSeconds(2), // generous: unit tests are not latency tests
        new ApiProperties.Budgets(
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)),
        new ApiProperties.Candidates(200, 30, Duration.ofDays(7), 5, 30, 100, 100, 0.7),
        new ApiProperties.Rerank(
            Duration.ofHours(2), 3, 2, 0.8, Duration.ofDays(14), 0.1, 0.3, 0.08, 8, 20),
        20,
        50,
        0.0,
        new ApiProperties.Experiments(
            "test",
            List.of(new ApiProperties.Variant("control", 0, 1000, RankerWeights.defaults()))));
  }

  private TestProps() {}
}
