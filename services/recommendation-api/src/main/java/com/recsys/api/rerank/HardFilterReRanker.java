package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.core.RecContext;
import com.recsys.features.model.UserShortTerm;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes items that must never be shown: consumed within the domain's window (songs replay after
 * hours; books are hidden for a year), disliked / not-interested items and creators, explicit
 * content when disallowed, region-unavailable items, the current seed item, unknown items, and —
 * where re-consumption makes no sense (familiarity cap 0) — items the user already liked.
 */
public final class HardFilterReRanker implements ReRanker {

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    UserShortTerm st = ctx.shortTerm();
    long now = ctx.now();
    long window = ctx.rules().consumedWindow().toMillis();
    Set<String> blockedItems = new HashSet<>();
    Map<String, Long> blockedCreators = Map.of();
    if (st != null) {
      st.consumed()
          .forEach(
              (id, ts) -> {
                if (now - ts < window) {
                  blockedItems.add(id);
                }
              });
      blockedItems.addAll(st.suppressedItems());
      if (ctx.rules().familiarityCap() <= 0) {
        blockedItems.addAll(st.liked());
      }
      blockedCreators = st.suppressedArtists();
    }
    if (ctx.request().seedItemId() != null) {
      blockedItems.add(ctx.request().seedItemId());
    }
    Map<String, Long> creators = blockedCreators;
    String region = ctx.region();
    boolean explicitAllowed = ctx.request().explicitAllowed();
    return ranked.stream()
        // Unknown items (deleted from catalog) are dropped; popular fallback items may lack
        // metadata
        // when Redis is down and are kept.
        .filter(c -> c.meta != null || c.payload != null || c.source(Sources.POPULAR_FALLBACK) > 0)
        .filter(c -> !blockedItems.contains(c.itemId))
        .filter(c -> c.artistId() == null || creators.getOrDefault(c.artistId(), 0L) < now)
        .filter(c -> explicitAllowed || c.meta == null || !c.meta.explicit())
        .filter(c -> c.meta == null || c.meta.availableIn(region))
        .toList();
  }
}
