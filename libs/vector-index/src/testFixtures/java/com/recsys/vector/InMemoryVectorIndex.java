package com.recsys.vector;

import com.recsys.common.Vectors;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Exact (brute force) in-memory index for unit tests. Same filter semantics as Qdrant. */
public class InMemoryVectorIndex implements VectorIndex {
  private final Map<String, Map<String, IndexedItem>> collections = new ConcurrentHashMap<>();
  private final Map<String, String> aliases = new ConcurrentHashMap<>();
  public volatile boolean failing;

  private Map<String, IndexedItem> col(String nameOrAlias) {
    check();
    String name = aliases.getOrDefault(nameOrAlias, nameOrAlias);
    return collections.computeIfAbsent(name, n -> new ConcurrentHashMap<>());
  }

  private void check() {
    if (failing) {
      throw new VectorIndexException("simulated outage", null);
    }
  }

  public int size(String collection) {
    return col(collection).size();
  }

  @Override
  public void ensureCollection(String collection, int dims, String alias) {
    col(collection);
    aliases.putIfAbsent(alias, collection);
  }

  @Override
  public void switchAlias(String alias, String collection) {
    aliases.put(alias, collection);
  }

  @Override
  public void upsert(String collection, List<IndexedItem> items) {
    items.forEach(i -> col(collection).put(i.payload().itemId(), i));
  }

  @Override
  public void setPayload(String collection, ItemPayload payload) {
    col(collection)
        .computeIfPresent(payload.itemId(), (k, v) -> new IndexedItem(payload, v.vector()));
  }

  @Override
  public void delete(String collection, List<String> itemIds) {
    itemIds.forEach(col(collection)::remove);
  }

  @Override
  public Map<String, String> contentHashes(String collection, List<String> itemIds) {
    Map<String, String> out = new HashMap<>();
    for (String id : itemIds) {
      IndexedItem i = col(collection).get(id);
      if (i != null) {
        out.put(id, i.payload().contentHash());
      }
    }
    return out;
  }

  @Override
  public Map<String, float[]> vectors(String collection, List<String> itemIds) {
    Map<String, float[]> out = new HashMap<>();
    for (String id : itemIds) {
      IndexedItem i = col(collection).get(id);
      if (i != null) {
        out.put(id, i.vector());
      }
    }
    return out;
  }

  @Override
  public List<VectorHit> search(
      String collection, float[] query, SearchFilter f, int limit, Duration timeout) {
    return col(collection).values().stream()
        .filter(i -> i.payload().domain().equals(f.domain()))
        .filter(
            i ->
                f.region() == null
                    || i.payload().regions().contains(ItemPayload.ALL_REGIONS)
                    || i.payload().regions().contains(f.region()))
        .filter(i -> f.allowExplicit() || !i.payload().explicit())
        .filter(i -> f.ingestedAfter() == null || i.payload().ingestedAt() >= f.ingestedAfter())
        .filter(i -> !f.excludeIds().contains(i.payload().itemId()))
        .map(
            i ->
                new VectorHit(i.payload().itemId(), Vectors.cosine(query, i.vector()), i.payload()))
        .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
        .limit(limit)
        .toList();
  }

  @Override
  public List<VectorHit> scoreIds(
      String collection, float[] query, List<String> itemIds, Duration timeout) {
    return itemIds.stream()
        .map(id -> col(collection).get(id))
        .filter(java.util.Objects::nonNull)
        .map(i -> new VectorHit(i.payload().itemId(), Vectors.cosine(query, i.vector()), null))
        .toList();
  }
}
