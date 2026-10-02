package com.recsys.features;

import com.recsys.features.model.Explanation;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.TrendingList;
import com.recsys.features.model.UserFeatures;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Serving-path reads. Every method takes a timeout and throws {@link FeatureStoreException} on
 * timeout/failure so the caller can degrade.
 */
public interface FeatureReader {
  UserFeatures user(String userId, String domain, Duration timeout);

  Map<String, ItemMeta> itemMeta(Collection<String> itemIds, Duration timeout);

  Map<String, ItemStats> itemStats(Collection<String> itemIds, Duration timeout);

  /** Neighbor lists for many seed items in one pipeline, keyed by seed item id. */
  Map<String, Neighbors> neighbors(List<String> itemIds, boolean next, Duration timeout);

  TrendingList trending(String domain, String region, Duration timeout);

  /**
   * Explanation texts by explanation key (see {@link RedisKeys#explanation}); missing keys are
   * absent.
   */
  Map<String, Explanation> explanations(Collection<String> keys, Duration timeout);
}
