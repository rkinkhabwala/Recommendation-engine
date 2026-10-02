package com.recsys.features;

import java.util.List;

/**
 * Redis key schema. User keys share the hash tag {@code {userId}} so all of a user's keys live in
 * one cluster slot: deletion is a single atomic script and needs no SCAN.
 */
public final class RedisKeys {
  private RedisKeys() {}

  public static String userShortTerm(String userId) {
    return "u:{" + userId + "}:st";
  }

  public static String userLongTerm(String userId) {
    return "u:{" + userId + "}:lt";
  }

  public static String userSeed(String userId) {
    return "u:{" + userId + "}:seed";
  }

  public static String userDeletedMarker(String userId) {
    return "u:{" + userId + "}:deleted";
  }

  /** Every per-user key; deletion removes all of them. */
  public static List<String> allUserKeys(String userId) {
    return List.of(userShortTerm(userId), userLongTerm(userId), userSeed(userId));
  }

  public static String itemStats(String itemId) {
    return "i:{" + itemId + "}:stat";
  }

  public static String itemNeighbors(String itemId) {
    return "i:{" + itemId + "}:i2i";
  }

  public static String itemNext(String itemId) {
    return "i:{" + itemId + "}:next";
  }

  public static String itemMeta(String itemId) {
    return "i:{" + itemId + "}:meta";
  }

  public static String trending(String domain, String region) {
    return "trend:" + domain + ":" + region;
  }

  /** Extracts the user id from a user key, or null for non-user keys. */
  public static String userIdOf(String key) {
    if (!key.startsWith("u:{")) {
      return null;
    }
    int end = key.indexOf('}');
    return end > 3 ? key.substring(3, end) : null;
  }
}
