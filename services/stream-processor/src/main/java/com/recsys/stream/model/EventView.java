package com.recsys.stream.model;

import com.recsys.events.v1.UserEvent;
import java.util.Locale;

/** Flattened, serde-friendly projection of a {@link UserEvent} used inside the topology. */
public record EventView(
    String eventId,
    String userId,
    String itemId,
    String domain,
    String eventType,
    Double value,
    long eventTs,
    long receivedTs,
    String sessionId,
    String country,
    Boolean autoplay,
    Long positionMs,
    Long durationMs,
    Long seekFromMs,
    Long seekToMs,
    String recommendationId,
    Integer position,
    String variantId) {

  public static EventView of(UserEvent e) {
    var c = e.getContext();
    var m = e.getMedia();
    return new EventView(
        e.getEventId(),
        e.getUserId(),
        e.getItemId(),
        e.getDomain().name().toLowerCase(Locale.ROOT),
        e.getEventType().name(),
        e.getValue(),
        e.getEventTs().toEpochMilli(),
        e.getReceivedTs().toEpochMilli(),
        e.getSessionId(),
        c == null ? null : c.getCountry(),
        c == null ? null : c.getAutoplay(),
        m == null ? null : m.getPositionMs(),
        m == null ? null : m.getDurationMs(),
        m == null ? null : m.getSeekFromMs(),
        m == null ? null : m.getSeekToMs(),
        e.getRecommendationId(),
        e.getPosition(),
        e.getVariantId());
  }

  /** Seconds listened/watched: explicit value, else playback position. */
  public Double listenedSeconds() {
    if (value != null) {
      return value;
    }
    return positionMs == null ? null : positionMs / 1000.0;
  }
}
