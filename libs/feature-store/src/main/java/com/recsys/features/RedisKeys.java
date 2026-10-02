package com.recsys.features;

import com.recsys.common.Domains;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Redis key schema. User keys share the hash tag {@code {userId}} so all of a user's keys live in
 * one cluster slot: deletion is a single atomic script and needs no SCAN.
 */
public final class RedisKeys {
  private RedisKeys() {}

  public static String userShortTerm(String userId, String domain) {
    return "u:{" + userId + "}:st:" + domain;
  }

  public static String userLongTerm(String userId, String domain) {
    return "u:{" + userId + "}:lt:" + domain;
  }

  public static String userSeed(String userId, String domain) {
    return "u:{" + userId + "}:seed:" + domain;
  }

  /** Cross-domain taste vector (all domains share one embedding space). */
  public static String userCrossDomain(String userId) {
    return "u:{" + userId + "}:x";
  }

  public static String userDeletedMarker(String userId) {
    return "u:{" + userId + "}:deleted";
  }

  /** Every per-user key; deletion removes all of them. */
  public static List<String> allUserKeys(String userId) {
    List<String> keys = new ArrayList<>();
    for (String d : Domains.ALL) {
      keys.add(userShortTerm(userId, d));
      keys.add(userLongTerm(userId, d));
      keys.add(userSeed(userId, d));
    }
    keys.add(userCrossDomain(userId));
    return keys;
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

  /**
   * Cached "why this" explanation. Keyed by what the text depends on (never by user), so one
   * generated sentence serves every user who gets the same recommendation for the same reason.
   */
  public static String explanation(
      String domain, String reasonCode, String seedItemId, String itemId) {
    try {
      byte[] h =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (domain + "|" + reasonCode + "|" + seedItemId + "|" + itemId)
                      .getBytes(StandardCharsets.UTF_8));
      return "expl:" + HexFormat.of().formatHex(h, 0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
