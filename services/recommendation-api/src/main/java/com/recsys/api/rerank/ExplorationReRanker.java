package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Exploration on ~ε of slots (never slot 0 on next_track) with new or under-exposed items from
 * outside the exploit list. Two strategies (per domain, overridable per A/B variant):
 *
 * <ul>
 *   <li>{@code epsilon}: uniform pick from the pool; propensity = slots / pool size.
 *   <li>{@code thompson}: sample each pool item's engagement rate from Beta(starts+1,
 *       impressions−starts+1) and pick the highest samples — new items (no evidence) explore
 *       broadly, items that keep failing stop being explored. Propensity is estimated by Monte
 *       Carlo so offline evaluation can still correct for it (IPS).
 * </ul>
 */
public final class ExplorationReRanker implements ReRanker {
  private static final int MAX_POOL = 30;
  private static final int PROPENSITY_DRAWS = 100;

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    var rules = ctx.rules();
    if (limit < rules.explorationMinLimit() || ranked.size() <= limit) {
      return ranked;
    }
    int slots = Math.max(1, (int) Math.round(rules.explorationEpsilon() * limit));
    List<ScoredCandidate> head = new ArrayList<>(ranked.subList(0, limit));
    Set<String> creatorsInHead = new HashSet<>();
    head.forEach(c -> creatorsInHead.add(c.artistId()));
    List<ScoredCandidate> pool =
        ranked.subList(limit, ranked.size()).stream()
            // Explore only with evidence: a new item, or one with known low exposure. Missing
            // stats alone (e.g. Redis down, popular fallback) is not evidence.
            .filter(
                c ->
                    c.source(Sources.FRESH) > 0
                        || (c.stats != null
                            && c.stats.impressions() < rules.lowImpressionThreshold()))
            .limit(MAX_POOL)
            .toList();
    // Prefer creators not already in the list (more novelty); fall back to the whole pool.
    List<ScoredCandidate> novel =
        pool.stream().filter(c -> !creatorsInHead.contains(c.artistId())).toList();
    if (!novel.isEmpty()) {
      pool = novel;
    }
    if (pool.isEmpty()) {
      return ranked;
    }
    // Seeded from user + time so explored items and slots vary across requests.
    Random random = new Random((ctx.request().userId() + ctx.now()).hashCode());
    int take = Math.min(slots, pool.size());
    List<ScoredCandidate> picks;
    List<Double> propensities = new ArrayList<>();
    if ("thompson".equals(ctx.explorationStrategy())) {
      picks = thompson(pool, take, random);
      for (ScoredCandidate p : picks) {
        propensities.add(thompsonPropensity(pool, p, take, random));
      }
    } else {
      List<ScoredCandidate> copy = new ArrayList<>(pool);
      picks = new ArrayList<>();
      for (int i = 0; i < take; i++) {
        picks.add(copy.remove(random.nextInt(copy.size())));
        propensities.add((double) take / pool.size());
      }
    }
    int minPos = ctx.request().nextTrack() ? 1 : 2;
    for (int i = 0; i < picks.size(); i++) {
      ScoredCandidate pick = picks.get(i);
      pick.explore = true;
      pick.propensity = propensities.get(i);
      pick.reason = ReasonCode.NEW_FOR_YOU;
      pick.seedItemId = null;
      int pos = minPos + random.nextInt(Math.max(1, limit - minPos));
      head.remove(head.size() - 1); // drop the weakest exploit item
      head.add(Math.min(pos, head.size()), pick);
    }
    return head;
  }

  static List<ScoredCandidate> thompson(List<ScoredCandidate> pool, int take, Random random) {
    return pool.stream()
        .map(c -> new Object[] {c, sample(c, random)})
        .sorted(Comparator.comparingDouble((Object[] o) -> (double) o[1]).reversed())
        .limit(take)
        .map(o -> (ScoredCandidate) o[0])
        .toList();
  }

  private static double thompsonPropensity(
      List<ScoredCandidate> pool, ScoredCandidate item, int take, Random random) {
    int hits = 0;
    for (int d = 0; d < PROPENSITY_DRAWS; d++) {
      if (thompson(pool, take, random).contains(item)) {
        hits++;
      }
    }
    return Math.max(1.0 / PROPENSITY_DRAWS, hits / (double) PROPENSITY_DRAWS);
  }

  /** Draw from Beta(starts + 1, impressions − starts + 1). */
  static double sample(ScoredCandidate c, Random random) {
    double starts = c.stats == null ? 0 : Math.max(0, c.stats.plays());
    double impressions = c.stats == null ? 0 : Math.max(starts, c.stats.impressions());
    return beta(starts + 1, impressions - starts + 1, random);
  }

  static double beta(double a, double b, Random r) {
    double x = gamma(a, r);
    double y = gamma(b, r);
    return x / (x + y);
  }

  /** Marsaglia–Tsang gamma sampler (shape ≥ 1 here since shapes are counts + 1). */
  static double gamma(double shape, Random r) {
    double d = shape - 1.0 / 3.0;
    double c = 1.0 / Math.sqrt(9 * d);
    while (true) {
      double x = r.nextGaussian();
      double v = 1 + c * x;
      if (v <= 0) {
        continue;
      }
      v = v * v * v;
      double u = r.nextDouble();
      if (u < 1 - 0.0331 * x * x * x * x || Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) {
        return d * v;
      }
    }
  }
}
