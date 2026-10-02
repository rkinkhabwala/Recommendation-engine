package com.recsys.features;

import com.recsys.features.model.Explanation;
import com.recsys.features.model.ItemMeta;
import com.recsys.features.model.ItemStats;
import com.recsys.features.model.Neighbors;
import com.recsys.features.model.TrendingList;
import com.recsys.features.model.UserFeatures;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Lettuce-backed feature store. Values are hashes {@code {seq, data}} where data is JSON. Reads are
 * pipelined (Lettuce auto-flushes async commands on one connection) and bounded by the caller's
 * timeout.
 */
public final class RedisFeatureStore implements FeatureReader, FeatureWriter {
  private final StatefulRedisConnection<String, byte[]> connection;
  private final String casScript = load("/lua/cas_upsert.lua");
  private final String deleteUserScript = load("/lua/delete_user.lua");
  private volatile String casSha;
  private volatile String deleteUserSha;

  public RedisFeatureStore(StatefulRedisConnection<String, byte[]> connection) {
    this.connection = connection;
  }

  private RedisAsyncCommands<String, byte[]> async() {
    return connection.async();
  }

  // ---------------------------------------------------------------- writes

  @Override
  public CompletableFuture<WriteResult> upsert(String key, long seq, byte[] data, long ttlSeconds) {
    String userId = RedisKeys.userIdOf(key);
    String[] keys =
        userId == null
            ? new String[] {key}
            : new String[] {key, RedisKeys.userDeletedMarker(userId)};
    byte[][] args = {ascii(seq), data, ascii(ttlSeconds)};
    return eval(casScript, true, keys, args)
        .thenApply(
            r -> {
              long code = (Long) r;
              return code == 1
                  ? WriteResult.APPLIED
                  : code == 0 ? WriteResult.STALE : WriteResult.USER_DELETED;
            });
  }

  @Override
  public CompletableFuture<Void> delete(String key) {
    return async().del(key).toCompletableFuture().thenApply(n -> null);
  }

  @Override
  public CompletableFuture<Void> deleteUser(String userId, Duration markerTtl) {
    List<String> keys = new ArrayList<>();
    keys.add(RedisKeys.userDeletedMarker(userId));
    keys.addAll(RedisKeys.allUserKeys(userId));
    return eval(
            deleteUserScript,
            false,
            keys.toArray(String[]::new),
            new byte[][] {ascii(markerTtl.toSeconds())})
        .thenApply(r -> null);
  }

  private CompletableFuture<Object> eval(String script, boolean cas, String[] keys, byte[][] args) {
    String sha = cas ? casSha : deleteUserSha;
    if (sha == null) {
      return async()
          .scriptLoad(script)
          .toCompletableFuture()
          .thenCompose(
              loaded -> {
                if (cas) {
                  casSha = loaded;
                } else {
                  deleteUserSha = loaded;
                }
                return eval(script, cas, keys, args);
              });
    }
    RedisFuture<Object> f = async().evalsha(sha, ScriptOutputType.INTEGER, keys, args);
    return f.toCompletableFuture()
        .exceptionallyCompose(
            e -> {
              // Script cache flushed (Redis restart / failover): reload and retry once.
              if (e instanceof RedisNoScriptException
                  || e.getCause() instanceof RedisNoScriptException) {
                if (cas) {
                  casSha = null;
                } else {
                  deleteUserSha = null;
                }
                return eval(script, cas, keys, args);
              }
              return CompletableFuture.failedFuture(e);
            });
  }

  // ---------------------------------------------------------------- reads

  @Override
  public UserFeatures user(String userId, String domain, Duration timeout) {
    var st = hgetData(RedisKeys.userShortTerm(userId, domain));
    var lt = hgetData(RedisKeys.userLongTerm(userId, domain));
    var seed = hgetData(RedisKeys.userSeed(userId, domain));
    var x = hgetData(RedisKeys.userCrossDomain(userId));
    await(List.of(st, lt, seed, x), timeout);
    return new UserFeatures(
        FeatureJson.read(st.join(), UserShortTerm.class),
        FeatureJson.read(lt.join(), UserVector.class),
        FeatureJson.read(seed.join(), UserVector.class),
        FeatureJson.read(x.join(), UserVector.class));
  }

  @Override
  public Map<String, Explanation> explanations(Collection<String> keys, Duration timeout) {
    return readMany(keys, k -> k, Explanation.class, timeout);
  }

  @Override
  public Map<String, ItemMeta> itemMeta(Collection<String> itemIds, Duration timeout) {
    return readMany(itemIds, RedisKeys::itemMeta, ItemMeta.class, timeout);
  }

  @Override
  public Map<String, ItemStats> itemStats(Collection<String> itemIds, Duration timeout) {
    return readMany(itemIds, RedisKeys::itemStats, ItemStats.class, timeout);
  }

  @Override
  public Map<String, Neighbors> neighbors(List<String> itemIds, boolean next, Duration timeout) {
    return readMany(
        itemIds, next ? RedisKeys::itemNext : RedisKeys::itemNeighbors, Neighbors.class, timeout);
  }

  @Override
  public TrendingList trending(String domain, String region, Duration timeout) {
    var f = hgetData(RedisKeys.trending(domain, region));
    await(List.of(f), timeout);
    return FeatureJson.read(f.join(), TrendingList.class);
  }

  private <T> Map<String, T> readMany(
      Collection<String> ids, Function<String, String> keyOf, Class<T> type, Duration timeout) {
    Map<String, CompletableFuture<byte[]>> futures = new LinkedHashMap<>();
    for (String id : ids) {
      futures.put(id, hgetData(keyOf.apply(id)));
    }
    await(futures.values(), timeout);
    Map<String, T> out = new HashMap<>();
    futures.forEach(
        (id, f) -> {
          byte[] bytes = f.join();
          if (bytes != null) {
            out.put(id, FeatureJson.read(bytes, type));
          }
        });
    return out;
  }

  private CompletableFuture<byte[]> hgetData(String key) {
    return async().hget(key, "data").toCompletableFuture();
  }

  private static void await(Collection<? extends CompletableFuture<?>> futures, Duration timeout) {
    try {
      CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
          .get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FeatureStoreException("interrupted", e);
    } catch (Exception e) {
      throw new FeatureStoreException("Redis read failed or timed out: " + e, e);
    }
  }

  private static byte[] ascii(long v) {
    return Long.toString(v).getBytes(StandardCharsets.US_ASCII);
  }

  private static String load(String resource) {
    try (InputStream in = RedisFeatureStore.class.getResourceAsStream(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
