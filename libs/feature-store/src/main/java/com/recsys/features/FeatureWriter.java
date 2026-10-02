package com.recsys.features;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Idempotent writes to the online feature store. */
public interface FeatureWriter {

  /**
   * Writes {@code data} under {@code key} only if {@code seq} is newer than the stored seq. For
   * user keys the write is refused while the user's deletion marker exists.
   */
  CompletableFuture<WriteResult> upsert(String key, long seq, byte[] data, long ttlSeconds);

  /** Unconditional delete of non-user keys (e.g. deleted catalog item). */
  CompletableFuture<Void> delete(String key);

  /** Deletes all of a user's keys and sets the deletion marker, atomically. */
  CompletableFuture<Void> deleteUser(String userId, Duration markerTtl);

  /** Batch helper: waits for all writes. */
  default void awaitAll(List<CompletableFuture<?>> futures, Duration timeout) throws Exception {
    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }
}
