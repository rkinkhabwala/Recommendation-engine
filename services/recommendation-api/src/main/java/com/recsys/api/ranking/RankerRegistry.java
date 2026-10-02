package com.recsys.api.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.api.config.ApiProperties.Variant;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model registry client. Layout (written by {@code ml/recsys_ml/train.py}):
 *
 * <pre>
 *   &lt;modelDir&gt;/ranker/&lt;domain&gt;/current          → "v20261002T1700" (promoted)
 *   &lt;modelDir&gt;/ranker/&lt;domain&gt;/candidate        → version eligible for A/B
 *   &lt;modelDir&gt;/ranker/&lt;domain&gt;/&lt;version&gt;/model.json     (LightGBM dump_model)
 *   &lt;modelDir&gt;/ranker/&lt;domain&gt;/&lt;version&gt;/metadata.json  (features, metrics, data window)
 * </pre>
 *
 * Polls {@code current} and hot-swaps atomically; a model whose feature list does not match {@link
 * FeatureExtractor#FEATURES} + position is refused. Variants asking for {@code lightgbm} fall back
 * to the heuristic when no valid model exists for the domain (and say so via the served ranker
 * version and {@code recs_api_ranker_fallback_total}).
 */
public final class RankerRegistry {
  private static final Logger log = LoggerFactory.getLogger(RankerRegistry.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final HeuristicRanker heuristic = new HeuristicRanker();
  private final Map<String, LightGbmRanker> models = new ConcurrentHashMap<>();
  private final Map<String, String> loadedVersions = new ConcurrentHashMap<>();
  private final Path root;
  private final MeterRegistry metrics;

  public RankerRegistry(String modelDir, MeterRegistry metrics) {
    this.root = modelDir == null ? null : Path.of(modelDir, "ranker");
    this.metrics = metrics;
  }

  private static final java.util.List<String> POINTERS = java.util.List.of("current", "candidate");

  public Ranker forVariant(String domain, Variant variant) {
    if (!variant.ranker().startsWith("lightgbm")) {
      return heuristic;
    }
    LightGbmRanker model = null;
    if (variant.ranker().equals("lightgbm:candidate")) {
      model = models.get(domain + "/candidate");
    }
    if (model == null) {
      model = models.get(domain + "/current");
    }
    if (model == null) {
      metrics
          .counter("recs_api_ranker_fallback_total", "domain", domain, "variant", variant.id())
          .increment();
      return heuristic;
    }
    return model;
  }

  public Map<String, String> loadedVersions() {
    return Map.copyOf(loadedVersions);
  }

  /** Loads new "current" versions; called on startup and on a schedule. Never throws. */
  public void reload() {
    if (root == null || !Files.isDirectory(root)) {
      return;
    }
    try (var domains = Files.list(root)) {
      for (Path dir : domains.filter(Files::isDirectory).toList()) {
        String domain = dir.getFileName().toString();
        for (String name : POINTERS) {
          String key = domain + "/" + name;
          try {
            Path pointer = dir.resolve(name);
            if (!Files.exists(pointer)) {
              continue;
            }
            String version = Files.readString(pointer).strip();
            if (version.equals(loadedVersions.get(key))) {
              continue;
            }
            models.put(key, load(dir.resolve(version), version));
            loadedVersions.put(key, version);
            log.info("Loaded ranker model {} as {} for domain {}", version, name, domain);
          } catch (Exception e) {
            log.error("Refusing ranker model {} for {}: {}", name, domain, e.toString());
            metrics.counter("recs_api_model_load_failures_total", "domain", domain).increment();
          }
        }
      }
    } catch (IOException e) {
      log.warn("Model registry not readable: {}", e.toString());
    }
  }

  static LightGbmRanker load(Path versionDir, String version) throws IOException {
    JsonNode meta = JSON.readTree(versionDir.resolve("metadata.json").toFile());
    List<String> features = new ArrayList<>();
    meta.path("features").forEach(f -> features.add(f.asText()));
    List<String> expected = new ArrayList<>(FeatureExtractor.FEATURES);
    expected.add("position");
    if (!features.equals(expected)) {
      throw new IllegalStateException(
          "feature mismatch: model " + features + " vs serving " + expected);
    }
    try (InputStream in = Files.newInputStream(versionDir.resolve("model.json"))) {
      LightGbmModel model = LightGbmModel.parse(in);
      if (!model.featureNames().isEmpty() && !model.featureNames().equals(expected)) {
        throw new IllegalStateException("model.json feature names differ from metadata");
      }
      return new LightGbmRanker(model, version);
    }
  }
}
