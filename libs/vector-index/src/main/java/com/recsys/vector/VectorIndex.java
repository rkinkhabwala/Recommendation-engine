package com.recsys.vector;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Vector index operations. Collections are versioned ({@code items_<model>_<dims>_v<n>}); serving
 * reads through an alias so a re-embedded collection can be swapped in without downtime. All writes
 * are idempotent: point ids are derived deterministically from item ids.
 */
public interface VectorIndex {

  /**
   * Creates the collection (cosine, given dims) and payload indexes if missing; points alias at it
   * if the alias is absent.
   */
  void ensureCollection(String collection, int dims, String alias);

  /** Atomically re-points {@code alias} to {@code collection}. */
  void switchAlias(String alias, String collection);

  void upsert(String collection, List<IndexedItem> items);

  /** Updates filter payload without touching the vector (e.g. region availability changed). */
  void setPayload(String collection, ItemPayload payload);

  void delete(String collection, List<String> itemIds);

  /** itemId → content hash for the items that exist in the collection. */
  Map<String, String> contentHashes(String collection, List<String> itemIds);

  /** itemId → vector for the items that exist. */
  Map<String, float[]> vectors(String collection, List<String> itemIds);

  List<VectorHit> search(
      String collection, float[] query, SearchFilter filter, int limit, Duration timeout);

  /**
   * Cosine scores of {@code query} against specific items (semantic rescore of non-ANN candidates).
   */
  List<VectorHit> scoreIds(
      String collection, float[] query, List<String> itemIds, Duration timeout);
}
