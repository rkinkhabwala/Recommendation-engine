package com.recsys.api.hydration;

import com.github.benmanes.caffeine.cache.Cache;
import com.recsys.api.candidates.ScoredCandidate;
import com.recsys.api.core.RecContext;
import com.recsys.features.FeatureReader;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.vector.VectorHit;
import com.recsys.vector.VectorIndex;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Attaches item metadata and statistics (in-process L1 cache first, then one Redis pipeline) and
 * computes semantic similarity for candidates that did not come from ANN (one id-filtered Qdrant
 * query). Each step degrades independently: missing data becomes an imputed feature.
 */
public final class Hydrator {
  private static final int MAX_RESCORE = 150;
  private final FeatureReader features;
  private final VectorIndex index;
  private final String indexAlias;
  private final Cache<String, ItemMeta> metaCache;
  private final Cache<String, ItemStats> statsCache;

  public Hydrator(
      FeatureReader features,
      VectorIndex index,
      String indexAlias,
      Cache<String, ItemMeta> metaCache,
      Cache<String, ItemStats> statsCache) {
    this.features = features;
    this.index = index;
    this.indexAlias = indexAlias;
    this.metaCache = metaCache;
    this.statsCache = statsCache;
  }

  /**
   * @return false if any part failed (response is PARTIAL)
   */
  public boolean hydrate(Collection<ScoredCandidate> candidates, Duration budget) {
    List<String> metaMisses = new ArrayList<>();
    List<String> statsMisses = new ArrayList<>();
    for (ScoredCandidate c : candidates) {
      c.meta = metaCache.getIfPresent(c.itemId);
      c.stats = statsCache.getIfPresent(c.itemId);
      if (c.meta == null) {
        metaMisses.add(c.itemId);
      }
      if (c.stats == null) {
        statsMisses.add(c.itemId);
      }
    }
    if (metaMisses.isEmpty() && statsMisses.isEmpty()) {
      return true;
    }
    try {
      Map<String, ItemMeta> metas =
          metaMisses.isEmpty() ? Map.of() : features.itemMeta(metaMisses, budget);
      Map<String, ItemStats> stats =
          statsMisses.isEmpty() ? Map.of() : features.itemStats(statsMisses, budget);
      metaCache.putAll(metas);
      statsCache.putAll(stats);
      for (ScoredCandidate c : candidates) {
        if (c.meta == null) {
          c.meta = metas.get(c.itemId);
        }
        if (c.stats == null) {
          c.stats = stats.get(c.itemId);
        }
      }
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  /**
   * @return false if the rescore failed or timed out
   */
  public boolean rescore(RecContext ctx, Collection<ScoredCandidate> candidates, Duration budget) {
    if (ctx.queryVector() == null) {
      return true;
    }
    List<String> ids =
        candidates.stream().filter(c -> c.semantic == null).map(c -> c.itemId).toList();
    if (ids.isEmpty()) {
      return true;
    }
    try {
      Map<String, Float> scores = new java.util.HashMap<>();
      for (VectorHit h : index.scoreIds(indexAlias, ctx.queryVector(), ids, budget)) {
        scores.put(h.itemId(), h.score());
      }
      for (ScoredCandidate c : candidates) {
        if (c.semantic == null) {
          c.semantic = scores.get(c.itemId);
        }
      }
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }
}
