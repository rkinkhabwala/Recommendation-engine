package com.recsys.api.ranking;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.core.RecContext;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.RecentInteraction;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weighted sum of normalized features: real-time semantic similarity, collaborative and session
 * signals, user affinities, popularity, completion rate and freshness, minus a penalty for artists
 * the user just rejected. Weights come from the request's A/B variant.
 */
public final class HeuristicRanker implements Ranker {
  public static final String VERSION = "heuristic-v1";
  private static final double FRESHNESS_DAYS = 90;

  @Override
  public String version() {
    return VERSION;
  }

  @Override
  public List<ScoredCandidate> rank(RecContext ctx, Collection<ScoredCandidate> candidates) {
    RankerWeights w =
        ctx.variant().weights() == null ? RankerWeights.defaults() : ctx.variant().weights();
    double maxArtist = maxPositive(ctx.artistAffinity());
    double maxGenre = maxPositive(ctx.genreAffinity());
    double maxMood = maxPositive(ctx.moodAffinity());
    Set<String> rejectedArtists = new HashSet<>();
    for (RecentInteraction r : ctx.recent()) {
      if (r.weight() < 0 && r.artistId() != null && ctx.now() - r.ts() < 3_600_000L) {
        rejectedArtists.add(r.artistId());
      }
    }
    LocalDate today = LocalDate.ofEpochDay(ctx.now() / 86_400_000L);

    for (ScoredCandidate c : candidates) {
      double semantic = c.semantic == null ? 0 : Math.max(0, c.semantic);
      double cf = c.source(Sources.ITEM_ITEM_CF);
      double next = c.source(Sources.NEXT_ITEM);
      double affinity = affinity(c, ctx, maxArtist, maxGenre, maxMood);
      double trending = Math.max(c.source(Sources.TRENDING), c.source(Sources.POPULAR_FALLBACK));
      double ctr = c.stats == null ? 0.1 : c.stats.ctr();
      double popularity = 0.5 * trending + 0.5 * Math.min(1, ctr / 0.3);
      double completion = c.stats == null ? 0.5 : c.stats.completionRate();
      double freshness = freshness(c.meta, today);
      double penalty = c.artistId() != null && rejectedArtists.contains(c.artistId()) ? 1 : 0;

      c.score =
          w.semantic() * semantic
              + w.cf() * cf
              + w.nextItem() * next
              + w.affinity() * affinity
              + w.popularity() * popularity
              + w.completion() * completion
              + w.freshness() * freshness
              - w.recentNegativePenalty() * penalty;
      if (!ctx.logFeatures()) {
        continue;
      }
      Map<String, Float> f = new LinkedHashMap<>();
      f.put("semantic", (float) semantic);
      f.put("cf", (float) cf);
      f.put("next", (float) next);
      f.put("affinity", (float) affinity);
      f.put("trending", (float) trending);
      f.put("ctr", (float) ctr);
      f.put("completion", (float) completion);
      f.put("freshness", (float) freshness);
      f.put("penalty", (float) penalty);
      c.features = f;
    }
    return candidates.stream()
        .sorted(Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed())
        .toList();
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
