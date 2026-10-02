package com.recsys.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.api.ranking.LightGbmModel;
import com.recsys.api.ranking.RankerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pure-Java evaluator must score exactly like LightGBM (fixture from ml/scripts). */
class LightGbmModelTest {
  static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void javaEvaluatorMatchesLightGbmPredictions() throws Exception {
    LightGbmModel model;
    try (var in = getClass().getResourceAsStream("/lgbm/model.json")) {
      model = LightGbmModel.parse(in);
    }
    JsonNode probe = JSON.readTree(getClass().getResourceAsStream("/lgbm/probe.json"));
    assertThat(model.treeCount()).isEqualTo(30);
    for (int i = 0; i < probe.get("inputs").size(); i++) {
      JsonNode row = probe.get("inputs").get(i);
      double[] x = new double[row.size()];
      for (int j = 0; j < x.length; j++) {
        x[j] = row.get(j).asDouble();
      }
      assertThat(model.predict(x)).isCloseTo(probe.get("expected").get(i).asDouble(), within(1e-9));
    }
  }

  @Test
  void registryHotSwapsPromotedVersionsAndRefusesFeatureMismatch(@TempDir Path dir)
      throws Exception {
    Path v1 = dir.resolve("ranker/song/v1");
    Files.createDirectories(v1);
    Files.copy(getClass().getResourceAsStream("/lgbm/model.json"), v1.resolve("model.json"));
    Files.copy(getClass().getResourceAsStream("/lgbm/metadata.json"), v1.resolve("metadata.json"));
    Files.writeString(dir.resolve("ranker/song/current"), "v1\n");

    Path bad = dir.resolve("ranker/book/v9");
    Files.createDirectories(bad);
    Files.copy(getClass().getResourceAsStream("/lgbm/model.json"), bad.resolve("model.json"));
    Files.writeString(bad.resolve("metadata.json"), "{\"features\":[\"semantic\",\"other\"]}");
    Files.writeString(dir.resolve("ranker/book/current"), "v9");

    var registry = new RankerRegistry(dir.toString(), new SimpleMeterRegistry());
    registry.reload();
    assertThat(registry.loadedVersions())
        .containsOnlyKeys("song/current")
        .containsEntry("song/current", "v1");
    var variant =
        new com.recsys.api.config.ApiProperties.Variant("t", 0, 1000, "lightgbm", null, null);
    assertThat(registry.forVariant("song", variant).version()).isEqualTo("lgbm-v1");
    assertThat(registry.forVariant("book", variant).version())
        .isEqualTo("heuristic-v1"); // refused → fallback
  }
}
