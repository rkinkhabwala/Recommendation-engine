package com.recsys.stream.model;

/**
 * Output of the user processor: a feature record for {@code redisKey} (null envelope = tombstone)
 * or a co-engagement observation.
 */
public record UserOut(String redisKey, byte[] envelope, CoEmit co) {
  public static UserOut feature(String key, byte[] envelope) {
    return new UserOut(key, envelope, null);
  }

  public static UserOut tombstone(String key) {
    return new UserOut(key, null, null);
  }

  public static UserOut co(CoEmit co) {
    return new UserOut(null, null, co);
  }

  public boolean isCo() {
    return co != null;
  }
}
