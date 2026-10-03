package com.recsys.ingestion.web;

import com.recsys.common.Ids;
import com.recsys.common.Topics;
import com.recsys.events.v1.Domain;
import com.recsys.events.v1.OnboardingSubmitted;
import com.recsys.events.v1.UserDeletionRequested;
import com.recsys.ingestion.IngestionProperties;
import com.recsys.ingestion.kafka.EventPublisher;
import com.recsys.ingestion.kafka.EventPublisher.Outgoing;
import com.recsys.ingestion.web.EventDtos.AcceptedResponse;
import com.recsys.ingestion.web.EventDtos.OnboardingRequest;
import com.recsys.web.ApiException;
import com.recsys.web.Principals;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Onboarding (cold start) and right-to-erasure endpoints. */
@RestController
public class UserController {
  private final EventPublisher publisher;
  private final IngestionProperties props;
  private final Clock clock;

  public UserController(EventPublisher publisher, IngestionProperties props, Clock clock) {
    this.publisher = publisher;
    this.props = props;
    this.clock = clock;
  }

  @PostMapping("/v1/users/{userId}/onboarding")
  public ResponseEntity<AcceptedResponse> onboarding(
      @PathVariable String userId, @RequestBody OnboardingRequest request) {
    requireUserId(userId);
    if (request.freeText() != null && request.freeText().length() > props.maxFreeTextChars()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "FREE_TEXT_TOO_LONG",
          "max " + props.maxFreeTextChars() + " chars");
    }
    var picks = request.picks();
    Instant now = clock.instant();
    var record =
        OnboardingSubmitted.newBuilder()
            .setUserId(userId)
            .setDomain(
                Domain.valueOf(
                    (request.domain() == null ? "song" : request.domain())
                        .toUpperCase(Locale.ROOT)))
            .setItemIds(picks == null || picks.itemIds() == null ? List.of() : ids(picks.itemIds()))
            .setArtistIds(
                picks == null || picks.artistIds() == null ? List.of() : ids(picks.artistIds()))
            .setGenres(
                picks == null || picks.genres() == null
                    ? List.of()
                    : picks.genres().stream().limit(20).toList())
            .setFreeText(request.freeText())
            .setSubmittedTs(now)
            .build();
    publishOrFail(new Outgoing(Topics.USERS_ONBOARDING, userId, now, record));
    return ResponseEntity.accepted().body(new AcceptedResponse(Ids.newId()));
  }

  /**
   * Starts deletion across all stores (see docs/architecture.md §9). TODO(phase-4): persist the
   * request in a deletion_requests table and expose completion status.
   */
  @DeleteMapping("/v1/users/{userId}/data")
  public ResponseEntity<AcceptedResponse> delete(@PathVariable String userId) {
    requireUserId(userId);
    Instant now = clock.instant();
    String requestId = Ids.newId();
    var record =
        UserDeletionRequested.newBuilder()
            .setRequestId(requestId)
            .setUserId(userId)
            .setRequestedTs(now)
            .build();
    publishOrFail(new Outgoing(Topics.USERS_DELETION, userId, now, record));
    return ResponseEntity.accepted().body(new AcceptedResponse(requestId));
  }

  private void publishOrFail(Outgoing out) {
    if (!publisher.publish(List.of(out), props.publishTimeout()).get(0)) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUBLISH_FAILED", "retry later");
    }
  }

  private static List<String> ids(List<String> ids) {
    return ids.stream().filter(i -> EventValidator.ID.matcher(i).matches()).limit(50).toList();
  }

  private static void requireUserId(String userId) {
    if (!EventValidator.ID.matcher(userId).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "invalid user id");
    }
    Principals.requireUser(userId);
  }
}
