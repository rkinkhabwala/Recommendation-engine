package com.recsys.api;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.ranking.RankerWeights;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class TestProps {
  static final String INDEX = "items_test_8_v1";

  static ApiProperties.Rerank rules(Duration consumed, double familiarity, String exploration) {
    return new ApiProperties.Rerank(
        consumed, 3, 2, 0.8, Duration.ofDays(14), 0.1, familiarity, 0.08, 8, 20, exploration);
  }

  static ApiProperties create() {
    return create("heuristic", "epsilon", null);
  }

  static ApiProperties create(String ranker, String exploration, String modelDir) {
    var variant =
        new ApiProperties.Variant("control", 0, 1000, ranker, RankerWeights.defaults(), null);
    return new ApiProperties(
        "items_current",
        INDEX,
        List.of("song", "book", "video", "post"),
        Duration.ofSeconds(2), // generous: unit tests are not latency tests
        new ApiProperties.Budgets(
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)),
        new ApiProperties.Candidates(200, 30, Duration.ofDays(7), 5, 30, 100, 100, 0.6, 0.25, 0.15),
        Map.of(
            "default", rules(Duration.ofDays(30), 0, exploration),
            "song", rules(Duration.ofHours(2), 0.3, exploration),
            "book", rules(Duration.ofDays(365), 0, exploration)),
        20,
        50,
        0.0,
        Map.of("default", new ApiProperties.Experiments("test", List.of(variant))),
        new ApiProperties.Ranking(modelDir, Duration.ofSeconds(30)));
  }

  private TestProps() {}
}
