package com.recsys.api.ranking;

import com.recsys.api.candidates.Candidate;
import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.core.ReasonCode;
import com.recsys.api.core.RecContext;
import java.util.Collection;

/**
 * Picks each item's reason from the signal that contributed most to its ranking score (weight ×
 * feature, with the variant's heuristic weights — also used as an interpretable proxy for the
 * learned ranker), instead of the highest raw source score: source scores have different scales
 * (trending is max-normalized to 1, cosine similarity rarely is), so "highest raw score" would
 * label taste-driven items TRENDING.
 */
final class ReasonAttribution {
  private ReasonAttribution() {}

  static void apply(RecContext ctx, Collection<ScoredCandidate> candidates) {
    RankerWeights w =
        ctx.variant().weights() == null ? RankerWeights.defaults() : ctx.variant().weights();
    for (ScoredCandidate c : candidates) {
      double[] f = c.featureVector;
      if (f == null) {
        continue;
      }
      double semantic = w.semantic() * f[0] + w.affinity() * f[3];
      double cf = w.cf() * f[1];
      double next = w.nextItem() * f[2];
      double popular = w.popularity() * f[4];
      double best = Math.max(Math.max(semantic, cf), Math.max(next, popular));
      if (best <= 0) {
        continue;
      }
      if (best == next && use(c, Sources.NEXT_ITEM)) {
        continue;
      }
      if (best == cf && use(c, Sources.ITEM_ITEM_CF)) {
        continue;
      }
      if (best == semantic && c.semantic != null) {
        Candidate fresh = c.bySource.get(Sources.FRESH);
        c.reason =
            fresh != null && !c.bySource.containsKey(Sources.SEMANTIC_ANN)
                ? ReasonCode.NEW_FOR_YOU
                : ctx.semanticReason();
        c.seedItemId = null;
        continue;
      }
      if (best == popular) {
        if (!use(c, Sources.TRENDING)) {
          use(c, Sources.POPULAR_FALLBACK);
        }
      }
    }
  }

  private static boolean use(ScoredCandidate c, String source) {
    Candidate proposal = c.bySource.get(source);
    if (proposal == null) {
      return false;
    }
    c.reason = proposal.reason();
    c.seedItemId = proposal.seedItemId();
    return true;
  }
}
