package com.recsys.ingestion.web;

import com.recsys.events.v1.Device;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.EventContext;
import com.recsys.events.v1.MediaProgress;
import com.recsys.events.v1.UserEvent;
import com.recsys.ingestion.web.EventDtos.ContextDto;
import com.recsys.ingestion.web.EventDtos.EventDto;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** JSON DTO → Avro. Applies server receive time and clock-skew clamping. */
public final class EventMapper {
  private EventMapper() {}

  public static UserEvent toAvro(EventDto e, Instant receivedTs, Duration maxSkew) {
    Instant eventTs = e.eventTs().isAfter(receivedTs.plus(maxSkew)) ? receivedTs : e.eventTs();
    ContextDto c = e.context();
    EventContext context =
        EventContext.newBuilder()
            .setDevice(c == null ? Device.UNKNOWN : device(c.device()))
            .setAppVersion(c == null ? null : c.appVersion())
            .setCountry(c == null ? null : c.country())
            .setTzOffsetMin(c == null ? null : c.tzOffsetMin())
            .setSurface(c == null ? null : c.surface())
            .setReferrer(c == null ? null : c.referrer())
            .setAutoplay(c == null ? null : c.autoplay())
            .build();
    MediaProgress media =
        e.media() == null
            ? null
            : MediaProgress.newBuilder()
                .setPositionMs(e.media().positionMs())
                .setDurationMs(e.media().durationMs())
                .setSeekFromMs(e.media().seekFromMs())
                .setSeekToMs(e.media().seekToMs())
                .build();
    return UserEvent.newBuilder()
        .setEventId(e.eventId().toLowerCase(Locale.ROOT))
        .setUserId(e.userId())
        .setItemId(e.itemId())
        .setDomain(Domain.valueOf(e.domain().toUpperCase(Locale.ROOT)))
        .setEventType(EventValidator.eventType(e.eventType()))
        .setValue(e.value())
        .setEventTs(eventTs)
        .setReceivedTs(receivedTs)
        .setSessionId(e.sessionId())
        .setContext(context)
        .setMedia(media)
        .setRecommendationId(e.recommendationId())
        .setPosition(e.position())
        .setVariantId(e.variantId())
        .setSearchQueryId(e.searchQueryId())
        .build();
  }

  static Device device(String s) {
    if (s == null) {
      return Device.UNKNOWN;
    }
    try {
      return Device.valueOf(s.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return Device.UNKNOWN;
    }
  }
}
