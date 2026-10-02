package com.recsys.features;

import com.recsys.features.model.Explanation;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.TrendingList;
import com.recsys.features.model.UserFeatures;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** In-memory feature store with the same CAS and deletion semantics as Redis, for unit tests. */
public class InMemoryFeatureStore implements FeatureReader, FeatureWriter {
  private record Entry(long seq, byte[] data) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final Map<String, Boolean> deletedUsers = new ConcurrentHashMap<>();
  public volatile boolean failing;

  @Override
  public synchronized CompletableFuture<WriteResult> upsert(
      String key, long seq, byte[] data, long ttlSeconds) {
    String userId = RedisKeys.userIdOf(key);
    if (userId != null && deletedUsers.containsKey(userId)) {
      return CompletableFuture.completedFuture(WriteResult.USER_DELETED);
    }
    Entry current = entries.get(key);
    if (current != null && current.seq >= seq) {
      return CompletableFuture.completedFuture(WriteResult.STALE);
    }
    entries.put(key, new Entry(seq, data));
    return CompletableFuture.completedFuture(WriteResult.APPLIED);
  }

  /** Test helper: write a typed value directly. */
  public void put(String key, Object value) {
    entries.put(key, new Entry(Long.MAX_VALUE / 2, FeatureJson.write(value)));
  }

  public boolean contains(String key) {
    return entries.containsKey(key);
  }

  public long seq(String key) {
    Entry e = entries.get(key);
    return e == null ? -1 : e.seq;
  }

  @Override
  public CompletableFuture<Void> delete(String key) {
    entries.remove(key);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public synchronized CompletableFuture<Void> deleteUser(String userId, Duration markerTtl) {
    RedisKeys.allUserKeys(userId).forEach(entries::remove);
    deletedUsers.put(userId, true);
    return CompletableFuture.completedFuture(null);
  }

  private <T> T get(String key, Class<T> type) {
    if (failing) {
      throw new FeatureStoreException("simulated outage", null);
    }
    Entry e = entries.get(key);
    return e == null ? null : FeatureJson.read(e.data, type);
  }

  @Override
  public UserFeatures user(String userId, String domain, Duration timeout) {
    return new UserFeatures(
        get(RedisKeys.userShortTerm(userId, domain), UserShortTerm.class),
        get(RedisKeys.userLongTerm(userId, domain), UserVector.class),
        get(RedisKeys.userSeed(userId, domain), UserVector.class),
        get(RedisKeys.userCrossDomain(userId), UserVector.class));
  }

  @Override
  public Map<String, Explanation> explanations(Collection<String> keys, Duration timeout) {
    return many(keys, k -> k, Explanation.class);
  }

  @Override
  public Map<String, ItemMeta> itemMeta(Collection<String> ids, Duration timeout) {
    return many(ids, RedisKeys::itemMeta, ItemMeta.class);
  }

  @Override
  public Map<String, ItemStats> itemStats(Collection<String> ids, Duration timeout) {
    return many(ids, RedisKeys::itemStats, ItemStats.class);
  }

  @Override
  public Map<String, Neighbors> neighbors(List<String> ids, boolean next, Duration timeout) {
    return many(ids, next ? RedisKeys::itemNext : RedisKeys::itemNeighbors, Neighbors.class);
  }

  @Override
  public TrendingList trending(String domain, String region, Duration timeout) {
    return get(RedisKeys.trending(domain, region), TrendingList.class);
  }

  private <T> Map<String, T> many(
      Collection<String> ids, Function<String, String> key, Class<T> t) {
    Map<String, T> out = new HashMap<>();
    for (String id : ids) {
      T v = get(key.apply(id), t);
      if (v != null) {
        out.put(id, v);
      }
    }
    return out;
  }
}
