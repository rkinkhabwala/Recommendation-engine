package com.recsys.stream.topology;

import com.recsys.common.DecayedScalar;
import com.recsys.features.model.ScoredItem;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Helpers for decayed maps. */
final class Features {
  private Features() {}

  static void add(Map<String, DecayedScalar> map, String key, double w, long ts, long halfLifeMs) {
    if (key == null || key.isBlank() || w == 0) {
      return;
    }
    map.computeIfAbsent(key, k -> new DecayedScalar()).add(ts, w, halfLifeMs);
  }

  /** Keeps the {@code max} entries with the largest |value| at {@code now}. */
  static void prune(Map<String, DecayedScalar> map, int max, long now, long halfLifeMs) {
    if (map.size() <= max) {
      return;
    }
    var keep =
        map.entrySet().stream()
            .sorted(
                Comparator.comparingDouble(
                        (Map.Entry<String, DecayedScalar> e) ->
                            Math.abs(e.getValue().valueAt(now, halfLifeMs)))
                    .reversed())
            .limit(max)
            .map(Map.Entry::getKey)
            .toList();
    map.keySet().retainAll(keep);
  }

  static Map<String, Double> snapshot(Map<String, DecayedScalar> map, long now, long halfLifeMs) {
    Map<String, Double> out = new LinkedHashMap<>();
    map.entrySet().stream()
        .sorted(
            Comparator.comparingDouble(
                    (Map.Entry<String, DecayedScalar> e) -> e.getValue().valueAt(now, halfLifeMs))
                .reversed())
        .forEach(e -> out.put(e.getKey(), round(e.getValue().valueAt(now, halfLifeMs))));
    return out;
  }

  /** Top-K by decayed value with min support, scores normalized to [0,1]. */
  static List<ScoredItem> topK(
      Map<String, DecayedScalar> map, int k, double minSupport, long now, long halfLifeMs) {
    var sorted =
        map.entrySet().stream()
            .map(e -> new ScoredItem(e.getKey(), e.getValue().valueAt(now, halfLifeMs)))
            .filter(s -> s.score() >= minSupport)
            .sorted(Comparator.comparingDouble(ScoredItem::score).reversed())
            .limit(k)
            .toList();
    if (sorted.isEmpty()) {
      return sorted;
    }
    double max = sorted.get(0).score();
    return sorted.stream().map(s -> new ScoredItem(s.itemId(), round(s.score() / max))).toList();
  }

  static double round(double v) {
    return Math.round(v * 10_000) / 10_000.0;
  }
}
