package com.recsys.api.ranking;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.core.RecContext;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.RecentInteraction;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the ranking feature vector of every candidate. Both rankers use it and the same values
 * are logged (sampled) on recs.served.v1, so the learned model trains on exactly what serving
 * computes — no training/serving skew by construction. Changing {@link #FEATURES} requires
 * retraining (the registry refuses models with a different feature list).
 */
public final class FeatureExtractor {
  public static final List<String> FEATURES =
      List.of(
          "semantic",
          "cf",
          "next",
          "affinity",
          "trending",
          "ctr",
          "completion",
          "freshness",
          "penalty");
  private static final double FRESHNESS_DAYS = 90;

  private FeatureExtractor() {}

  public static void extract(RecContext ctx, Collection<ScoredCandidate> candidates) {
    double maxArtist = maxPositive(ctx.artistAffinity());
    double maxGenre = maxPositive(ctx.genreAffinity());
    double maxMood = maxPositive(ctx.moodAffinity());
    Set<String> rejectedCreators = new HashSet<>();
    for (RecentInteraction r : ctx.recent()) {
      if (r.weight() < 0 && r.artistId() != null && ctx.now() - r.ts() < 3_600_000L) {
        rejectedCreators.add(r.artistId());
      }
    }
    LocalDate today = LocalDate.ofEpochDay(ctx.now() / 86_400_000L);
    for (ScoredCandidate c : candidates) {
      double ctr = c.stats == null ? 0.1 : c.stats.ctr();
      double[] f = {
        c.semantic == null ? 0 : Math.max(0, c.semantic),
        c.source(Sources.ITEM_ITEM_CF),
        c.source(Sources.NEXT_ITEM),
        affinity(c, ctx, maxArtist, maxGenre, maxMood),
        Math.max(c.source(Sources.TRENDING), c.source(Sources.POPULAR_FALLBACK)),
        ctr,
        c.stats == null ? 0.5 : c.stats.completionRate(),
        freshness(c.meta, today),
        c.artistId() != null && rejectedCreators.contains(c.artistId()) ? 1 : 0
      };
      c.featureVector = f;
      if (ctx.logFeatures()) {
        Map<String, Float> m = new LinkedHashMap<>();
        for (int i = 0; i < f.length; i++) {
          m.put(FEATURES.get(i), (float) f[i]);
        }
        c.features = m;
      }
    }
  }

  private static double affinity(
      ScoredCandidate c, RecContext ctx, double maxA, double maxG, double maxM) {
    String artistId = c.artistId();
    double artist = artistId == null ? 0 : norm(ctx.artistAffinity().get(artistId), maxA);
    List<String> genres =
        c.meta != null ? c.meta.genres() : c.payload != null ? c.payload.genres() : List.of();
    List<String> moods =
        c.meta != null ? c.meta.moods() : c.payload != null ? c.payload.moods() : List.of();
    double genre =
        genres.stream().mapToDouble(g -> norm(ctx.genreAffinity().get(g), maxG)).max().orElse(0);
    double mood =
        moods.stream().mapToDouble(m -> norm(ctx.moodAffinity().get(m), maxM)).max().orElse(0);
    return 0.5 * artist + 0.3 * genre + 0.2 * mood;
  }

  private static double norm(Double v, double max) {
    return v == null || v <= 0 || max <= 0 ? 0 : v / max;
  }

  private static double maxPositive(Map<String, Double> m) {
    return m.values().stream().mapToDouble(Double::doubleValue).filter(v -> v > 0).max().orElse(0);
  }

  private static double freshness(ItemMeta meta, LocalDate today) {
    if (meta == null || meta.releaseDate() == null) {
      return 0.3;
    }
    long days = Math.max(0, ChronoUnit.DAYS.between(meta.releaseDate(), today));
    return Math.exp(-days / FRESHNESS_DAYS);
  }
}
