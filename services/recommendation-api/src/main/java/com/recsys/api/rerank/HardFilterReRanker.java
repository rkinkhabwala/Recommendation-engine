package com.recsys.api.rerank;

import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.candidates.Sources;
import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecContext;
import com.recsys.features.model.UserShortTerm;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes items that must never be shown: recently played (songs are replayable, so only within the
 * window), disliked / not-interested items and artists, explicit content when disallowed,
 * region-unavailable items, the currently playing seed, and unknown items.
 */
public final class HardFilterReRanker implements ReRanker {
  private final ApiProperties.Rerank props;

  public HardFilterReRanker(ApiProperties.Rerank props) {
    this.props = props;
  }

  @Override
  public List<ScoredCandidate> apply(RecContext ctx, List<ScoredCandidate> ranked, int limit) {
    UserShortTerm st = ctx.shortTerm();
    long now = ctx.now();
    long window = props.recentlyPlayedWindow().toMillis();
    Set<String> blockedItems = new HashSet<>();
    Map<String, Long> blockedArtists = Map.of();
    if (st != null) {
      st.recentlyPlayed()
          .forEach(
              (id, ts) -> {
                if (now - ts < window) {
                  blockedItems.add(id);
                }
              });
      blockedItems.addAll(st.suppressedItems());
      blockedArtists = st.suppressedArtists();
    }
    if (ctx.request().seedItemId() != null) {
      blockedItems.add(ctx.request().seedItemId());
    }
    Map<String, Long> artists = blockedArtists;
    String region = ctx.region();
    boolean explicitAllowed = ctx.request().explicitAllowed();
    return ranked.stream()
        // Unknown items (deleted from catalog) are dropped; popular fallback items may lack
        // metadata
        // when Redis is down and are kept.
        .filter(c -> c.meta != null || c.payload != null || c.source(Sources.POPULAR_FALLBACK) > 0)
        .filter(c -> !blockedItems.contains(c.itemId))
        .filter(c -> c.artistId() == null || artists.getOrDefault(c.artistId(), 0L) < now)
        .filter(c -> explicitAllowed || c.meta == null || !c.meta.explicit())
        .filter(c -> c.meta == null || c.meta.availableIn(region))
        .toList();
  }
}
