package com.recsys.vector;

import static io.qdrant.client.ConditionFactory.hasId;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.ConditionFactory.range;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.list;
import static io.qdrant.client.ValueFactory.value;

import com.google.common.util.concurrent.ListenableFuture;
import com.recsys.common.Ids;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Collections.AliasOperations;
import io.qdrant.client.grpc.Collections.CreateAlias;
import io.qdrant.client.grpc.Collections.DeleteAlias;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.VectorOutput;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Qdrant (gRPC) implementation of {@link VectorIndex}. */
public final class QdrantVectorIndex implements VectorIndex {
  private static final Logger log = LoggerFactory.getLogger(QdrantVectorIndex.class);
  private static final String POINT_NAMESPACE = "item";

  /** Only what serving needs from a hit (artist/genres/moods for diversity and affinity). */
  private static final List<String> SEARCH_PAYLOAD =
      List.of("item_id", "artist_id", "genres", "moods", "domain");

  private final QdrantClient client;
  private final Duration writeTimeout;

  public QdrantVectorIndex(QdrantClient client, Duration writeTimeout) {
    this.client = client;
    this.writeTimeout = writeTimeout;
  }

  static PointId pointId(String itemId) {
    return id(Ids.nameBased(POINT_NAMESPACE, itemId));
  }

  @Override
  public void ensureCollection(String collection, int dims, String alias) {
    if (!await(client.collectionExistsAsync(collection), writeTimeout)) {
      log.info("Creating Qdrant collection {} ({} dims, cosine)", collection, dims);
      await(
          client.createCollectionAsync(
              collection,
              VectorParams.newBuilder().setDistance(Distance.Cosine).setSize(dims).build()),
          writeTimeout);
      for (String field : List.of("domain", "regions", "artist_id", "genres")) {
        index(collection, field, PayloadSchemaType.Keyword);
      }
      index(collection, "explicit", PayloadSchemaType.Bool);
      index(collection, "ingested_at", PayloadSchemaType.Integer);
    }
    boolean aliasExists =
        await(client.listAliasesAsync(), writeTimeout).stream()
            .anyMatch(a -> a.getAliasName().equals(alias));
    if (!aliasExists) {
      log.info("Pointing alias {} at {}", alias, collection);
      await(client.createAliasAsync(alias, collection), writeTimeout);
    }
  }

  private void index(String collection, String field, PayloadSchemaType type) {
    await(
        client.createPayloadIndexAsync(collection, field, type, null, true, null, writeTimeout),
        writeTimeout);
  }

  @Override
  public void switchAlias(String alias, String collection) {
    // Delete + create in one request: readers see either the old or the new collection.
    await(
        client.updateAliasesAsync(
            List.of(
                AliasOperations.newBuilder()
                    .setDeleteAlias(DeleteAlias.newBuilder().setAliasName(alias))
                    .build(),
                AliasOperations.newBuilder()
                    .setCreateAlias(
                        CreateAlias.newBuilder().setAliasName(alias).setCollectionName(collection))
                    .build())),
        writeTimeout);
  }

  @Override
  public void upsert(String collection, List<IndexedItem> items) {
    if (items.isEmpty()) {
      return;
    }
    List<PointStruct> points =
        items.stream()
            .map(
                i ->
                    PointStruct.newBuilder()
                        .setId(pointId(i.payload().itemId()))
                        .setVectors(VectorsFactory.vectors(i.vector()))
                        .putAllPayload(toPayload(i.payload()))
                        .build())
            .toList();
    await(client.upsertAsync(collection, points, writeTimeout), writeTimeout);
  }

  @Override
  public void setPayload(String collection, ItemPayload payload) {
    await(
        client.setPayloadAsync(
            collection, toPayload(payload), pointId(payload.itemId()), true, null, writeTimeout),
        writeTimeout);
  }

  @Override
  public void delete(String collection, List<String> itemIds) {
    if (itemIds.isEmpty()) {
      return;
    }
    await(
        client.deleteAsync(
            collection, itemIds.stream().map(QdrantVectorIndex::pointId).toList(), writeTimeout),
        writeTimeout);
  }

  @Override
  public Map<String, String> contentHashes(String collection, List<String> itemIds) {
    Map<String, String> out = new HashMap<>();
    for (RetrievedPoint p : retrieve(collection, itemIds, false)) {
      Map<String, Value> payload = p.getPayloadMap();
      if (payload.containsKey("item_id") && payload.containsKey("content_hash")) {
        out.put(
            payload.get("item_id").getStringValue(), payload.get("content_hash").getStringValue());
      }
    }
    return out;
  }

  @Override
  public Map<String, float[]> vectors(String collection, List<String> itemIds) {
    Map<String, float[]> out = new HashMap<>();
    for (RetrievedPoint p : retrieve(collection, itemIds, true)) {
      Value itemId = p.getPayloadMap().get("item_id");
      if (itemId != null && p.hasVectors()) {
        out.put(itemId.getStringValue(), toArray(p.getVectors().getVector()));
      }
    }
    return out;
  }

  private List<RetrievedPoint> retrieve(
      String collection, List<String> itemIds, boolean withVectors) {
    if (itemIds.isEmpty()) {
      return List.of();
    }
    return await(
        client.retrieveAsync(
            collection,
            itemIds.stream().map(QdrantVectorIndex::pointId).toList(),
            WithPayloadSelectorFactory.include(List.of("item_id", "content_hash")),
            WithVectorsSelectorFactory.enable(withVectors),
            null,
            writeTimeout),
        writeTimeout);
  }

  @Override
  public List<VectorHit> search(
      String collection, float[] query, SearchFilter filter, int limit, Duration timeout) {
    Filter.Builder f = Filter.newBuilder().addMust(matchKeyword("domain", filter.domain()));
    if (filter.region() != null) {
      f.addMust(matchKeywords("regions", List.of(ItemPayload.ALL_REGIONS, filter.region())));
    }
    if (!filter.allowExplicit()) {
      f.addMust(match("explicit", false));
    }
    if (filter.ingestedAfter() != null) {
      f.addMust(range("ingested_at", Range.newBuilder().setGte(filter.ingestedAfter()).build()));
    }
    if (!filter.excludeIds().isEmpty()) {
      f.addMustNot(hasId(filter.excludeIds().stream().map(QdrantVectorIndex::pointId).toList()));
    }
    QueryPoints request =
        QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setQuery(QueryFactory.nearest(query))
            .setFilter(f)
            .setLimit(limit)
            .setWithPayload(WithPayloadSelectorFactory.include(SEARCH_PAYLOAD))
            .build();
    return await(client.queryAsync(request, timeout), timeout).stream()
        .map(QdrantVectorIndex::toHit)
        .toList();
  }

  @Override
  public List<VectorHit> scoreIds(
      String collection, float[] query, List<String> itemIds, Duration timeout) {
    if (itemIds.isEmpty()) {
      return List.of();
    }
    QueryPoints request =
        QueryPoints.newBuilder()
            .setCollectionName(collection)
            .setQuery(QueryFactory.nearest(query))
            .setFilter(
                Filter.newBuilder()
                    .addMust(hasId(itemIds.stream().map(QdrantVectorIndex::pointId).toList())))
            .setLimit(itemIds.size())
            .setWithPayload(WithPayloadSelectorFactory.include(List.of("item_id")))
            .build();
    return await(client.queryAsync(request, timeout), timeout).stream()
        .map(
            p ->
                new VectorHit(
                    p.getPayloadMap().get("item_id").getStringValue(), p.getScore(), null))
        .toList();
  }

  private static VectorHit toHit(ScoredPoint p) {
    return new VectorHit(
        p.getPayloadMap().get("item_id").getStringValue(),
        p.getScore(),
        fromPayload(p.getPayloadMap()));
  }

  static Map<String, Value> toPayload(ItemPayload p) {
    Map<String, Value> m = new HashMap<>();
    m.put("item_id", value(p.itemId()));
    m.put("domain", value(p.domain()));
    m.put("artist_id", value(p.artistId()));
    m.put("genres", list(p.genres().stream().map(io.qdrant.client.ValueFactory::value).toList()));
    m.put("moods", list(p.moods().stream().map(io.qdrant.client.ValueFactory::value).toList()));
    m.put("explicit", value(p.explicit()));
    m.put("regions", list(p.regions().stream().map(io.qdrant.client.ValueFactory::value).toList()));
    m.put("ingested_at", value(p.ingestedAt()));
    m.put("content_hash", value(p.contentHash()));
    m.put("index_version", value(p.indexVersion()));
    return m;
  }

  static ItemPayload fromPayload(Map<String, Value> m) {
    return new ItemPayload(
        m.get("item_id").getStringValue(),
        str(m, "domain"),
        str(m, "artist_id"),
        strings(m, "genres"),
        strings(m, "moods"),
        m.containsKey("explicit") && m.get("explicit").getBoolValue(),
        strings(m, "regions"),
        m.containsKey("ingested_at") ? m.get("ingested_at").getIntegerValue() : 0L,
        str(m, "content_hash"),
        str(m, "index_version"));
  }

  private static String str(Map<String, Value> m, String key) {
    Value v = m.get(key);
    return v == null ? "" : v.getStringValue();
  }

  private static List<String> strings(Map<String, Value> m, String key) {
    Value v = m.get(key);
    if (v == null || !v.hasListValue()) {
      return List.of();
    }
    return v.getListValue().getValuesList().stream().map(Value::getStringValue).toList();
  }

  @SuppressWarnings("deprecation")
  private static float[] toArray(VectorOutput v) {
    List<Float> data = v.hasDense() ? v.getDense().getDataList() : v.getDataList();
    float[] out = new float[data.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = data.get(i);
    }
    return out;
  }

  private static <T> T await(ListenableFuture<T> future, Duration timeout) {
    try {
      return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new VectorIndexException("Interrupted", e);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new VectorIndexException(
          "Qdrant call timed out after " + timeout.toMillis() + " ms", e);
    } catch (ExecutionException e) {
      throw new VectorIndexException(
          "Qdrant call failed: " + e.getCause().getMessage(), e.getCause());
    }
  }
}
