package com.recsys.ingestion.web;

import java.time.Instant;
import java.util.List;

/** Public JSON contract of POST /v1/events (camelCase). Clients never see Avro. */
public final class EventDtos {
  private EventDtos() {}

  public record EventBatchRequest(List<EventDto> events) {}

  public record EventDto(
      String eventId,
      String userId,
      String itemId,
      String domain,
      String eventType,
      Double value,
      Instant eventTs,
      String sessionId,
      ContextDto context,
      MediaDto media,
      String recommendationId,
      Integer position,
      String variantId,
      String searchQueryId) {}

  public record ContextDto(
      String device,
      String appVersion,
      String country,
      Integer tzOffsetMin,
      String surface,
      String referrer,
      Boolean autoplay) {}

  public record MediaDto(Long positionMs, Long durationMs, Long seekFromMs, Long seekToMs) {}

  public record Rejection(int index, String eventId, String error) {}

  public record EventBatchResponse(int accepted, List<Rejection> rejected) {}

  public record OnboardingRequest(String domain, Picks picks, String freeText) {}

  public record Picks(List<String> itemIds, List<String> artistIds, List<String> genres) {}

  public record AcceptedResponse(String requestId) {}
}
