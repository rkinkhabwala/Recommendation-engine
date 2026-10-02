package com.recsys.ingestion.web;

import com.recsys.common.Ids;
import com.recsys.events.v1.EventType;
import com.recsys.ingestion.web.EventDtos.EventDto;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Per-event validation; returns an error code or null. Partial batches are accepted. */
public final class EventValidator {
  public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Set<String> DOMAINS = Set.of("song", "book", "video", "post");

  private EventValidator() {}

  public static String validate(EventDto e) {
    if (e == null) {
      return "NULL_EVENT";
    }
    if (!Ids.isUuid(e.eventId())) {
      return "INVALID_EVENT_ID";
    }
    if (e.userId() == null || !ID.matcher(e.userId()).matches()) {
      return "INVALID_USER_ID";
    }
    if (e.domain() == null || !DOMAINS.contains(e.domain().toLowerCase(Locale.ROOT))) {
      return "INVALID_DOMAIN";
    }
    EventType type = eventType(e.eventType());
    if (type == null || type == EventType.UNKNOWN) {
      return "INVALID_EVENT_TYPE";
    }
    boolean needsItem = type != EventType.SEARCH;
    if (needsItem && (e.itemId() == null || !ID.matcher(e.itemId()).matches())) {
      return "INVALID_ITEM_ID";
    }
    if (e.sessionId() == null || e.sessionId().isBlank() || e.sessionId().length() > 128) {
      return "MISSING_SESSION_ID";
    }
    if (e.eventTs() == null) {
      return "MISSING_EVENT_TS";
    }
    if (e.value() != null && (e.value().isNaN() || e.value().isInfinite() || e.value() < 0)) {
      return "INVALID_VALUE";
    }
    if (type == EventType.RATE && (e.value() == null || e.value() < 1 || e.value() > 5)) {
      return "INVALID_RATING";
    }
    if (e.position() != null && e.position() < 0) {
      return "INVALID_POSITION";
    }
    if (e.recommendationId() != null && e.recommendationId().length() > 64) {
      return "INVALID_RECOMMENDATION_ID";
    }
    if (e.context() != null
        && e.context().country() != null
        && !COUNTRY.matcher(e.context().country()).matches()) {
      return "INVALID_COUNTRY";
    }
    return null;
  }

  public static EventType eventType(String s) {
    if (s == null) {
      return null;
    }
    try {
      return EventType.valueOf(s.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
