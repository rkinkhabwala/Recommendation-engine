package com.recsys.sim;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Builds event JSON in the public ingestion contract. */
final class Events {
  private Events() {}

  static Map<String, Object> event(
      UserProfile u,
      String session,
      Item s,
      String type,
      Double value,
      Instant ts,
      String recId,
      Integer pos,
      String variant,
      Boolean autoplay) {
    Map<String, Object> e = new HashMap<>();
    e.put("eventId", uuidV7(ts.toEpochMilli()));
    e.put("userId", u.userId());
    e.put("itemId", s.id());
    e.put("domain", s.domain());
    e.put("eventType", type);
    if (value != null) {
      e.put("value", value);
    }
    e.put("eventTs", ts.toString());
    e.put("sessionId", session);
    Map<String, Object> ctx = new HashMap<>();
    ctx.put("device", u.device());
    ctx.put("country", u.country());
    ctx.put("surface", recId == null ? "search" : surface(s.domain()));
    ctx.put("autoplay", autoplay);
    e.put("context", ctx);
    if (s.durationMs() != null) {
      e.put(
          "media",
          Map.of(
              "durationMs",
              s.durationMs(),
              "positionMs",
              value == null ? 0 : (long) (value * 1000)));
    }
    if (recId != null) {
      e.put("recommendationId", recId);
      e.put("position", pos);
      e.put("variantId", variant);
    }
    return e;
  }

  static String surface(String domain) {
    return switch (domain) {
      case "song" -> "next_track";
      case "video" -> "related";
      case "post" -> "feed";
      default -> "home";
    };
  }

  static String uuidV7(long millis) {
    var r = java.util.concurrent.ThreadLocalRandom.current();
    long msb = ((millis & 0xFFFF_FFFF_FFFFL) << 16) | 0x7000L | (r.nextLong() & 0x0FFFL);
    long lsb = (r.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
    return new UUID(msb, lsb).toString();
  }
}
