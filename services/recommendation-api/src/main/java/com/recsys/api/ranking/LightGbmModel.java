package com.recsys.api.ranking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java evaluator for LightGBM models exported with {@code booster.dump_model()} (JSON). No
 * native library on the serving path; trees are flattened into arrays for cache-friendly scoring.
 * Supports numerical splits ({@code <=}) with LightGBM's missing-value handling.
 */
public final class LightGbmModel {
  private static final ObjectMapper JSON = new ObjectMapper();

  /** One flattened tree: node i is a leaf when {@code left[i] < 0}. */
  private record Tree(
      int[] feature,
      double[] threshold,
      boolean[] defaultLeft,
      int[] missingType,
      int[] left,
      int[] right,
      double[] value) {
    double predict(double[] x) {
      int n = 0;
      while (left[n] >= 0) {
        double v = x[feature[n]];
        boolean goLeft;
        if (Double.isNaN(v)) {
          goLeft = missingType[n] == MISSING_NAN ? defaultLeft[n] : 0.0 <= threshold[n];
        } else if (missingType[n] == MISSING_ZERO && v == 0.0) {
          goLeft = defaultLeft[n];
        } else {
          goLeft = v <= threshold[n];
        }
        n = goLeft ? left[n] : right[n];
      }
      return value[n];
    }
  }

  private static final int MISSING_NONE = 0;
  private static final int MISSING_ZERO = 1;
  private static final int MISSING_NAN = 2;

  private final List<Tree> trees;
  private final List<String> featureNames;

  private LightGbmModel(List<Tree> trees, List<String> featureNames) {
    this.trees = trees;
    this.featureNames = featureNames;
  }

  public static LightGbmModel parse(InputStream json) throws IOException {
    return parse(JSON.readTree(json));
  }

  public static LightGbmModel parse(JsonNode root) {
    List<String> names = new ArrayList<>();
    root.path("feature_names").forEach(n -> names.add(n.asText()));
    List<Tree> trees = new ArrayList<>();
    for (JsonNode t : root.path("tree_info")) {
      trees.add(flatten(t.path("tree_structure")));
    }
    if (trees.isEmpty()) {
      throw new IllegalArgumentException("model has no trees");
    }
    return new LightGbmModel(trees, List.copyOf(names));
  }

  private static Tree flatten(JsonNode root) {
    List<JsonNode> nodes = new ArrayList<>();
    collect(root, nodes);
    int n = nodes.size();
    int[] feature = new int[n];
    double[] threshold = new double[n];
    boolean[] defaultLeft = new boolean[n];
    int[] missing = new int[n];
    int[] left = new int[n];
    int[] right = new int[n];
    double[] value = new double[n];
    java.util.IdentityHashMap<JsonNode, Integer> index = new java.util.IdentityHashMap<>();
    for (int i = 0; i < n; i++) {
      index.put(nodes.get(i), i);
    }
    for (int i = 0; i < n; i++) {
      JsonNode node = nodes.get(i);
      if (node.has("leaf_value")) {
        left[i] = -1;
        right[i] = -1;
        value[i] = node.get("leaf_value").asDouble();
      } else {
        if (!"<=".equals(node.path("decision_type").asText("<="))) {
          throw new IllegalArgumentException("only numerical '<=' splits are supported");
        }
        feature[i] = node.get("split_feature").asInt();
        threshold[i] = node.get("threshold").asDouble();
        defaultLeft[i] = node.path("default_left").asBoolean(true);
        missing[i] =
            switch (node.path("missing_type").asText("None")) {
              case "Zero" -> MISSING_ZERO;
              case "NaN" -> MISSING_NAN;
              default -> MISSING_NONE;
            };
        left[i] = index.get(node.get("left_child"));
        right[i] = index.get(node.get("right_child"));
      }
    }
    return new Tree(feature, threshold, defaultLeft, missing, left, right, value);
  }

  private static void collect(JsonNode node, List<JsonNode> out) {
    out.add(node);
    if (!node.has("leaf_value")) {
      collect(node.get("left_child"), out);
      collect(node.get("right_child"), out);
    }
  }

  /** Raw score (sum of tree outputs); for lambdarank only the order matters. */
  public double predict(double[] x) {
    double s = 0;
    for (Tree t : trees) {
      s += t.predict(x);
    }
    return s;
  }

  public List<String> featureNames() {
    return featureNames;
  }

  public int treeCount() {
    return trees.size();
  }
}
