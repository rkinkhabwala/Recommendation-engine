package com.recsys.ingestion.web;

import com.recsys.common.Topics;
import com.recsys.events.v1.UserEvent;
import com.recsys.ingestion.IngestionProperties;
import com.recsys.ingestion.kafka.EventPublisher;
import com.recsys.ingestion.kafka.EventPublisher.Outgoing;
import com.recsys.ingestion.ratelimit.UserRateLimiter;
import com.recsys.ingestion.web.EventDtos.EventBatchRequest;
import com.recsys.ingestion.web.EventDtos.EventBatchResponse;
import com.recsys.ingestion.web.EventDtos.EventDto;
import com.recsys.ingestion.web.EventDtos.Rejection;
import com.recsys.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {
  private final EventPublisher publisher;
  private final IngestionProperties props;
  private final UserRateLimiter rateLimiter;
  private final MeterRegistry metrics;
  private final Clock clock;

  public EventController(
      EventPublisher publisher,
      IngestionProperties props,
      UserRateLimiter rateLimiter,
      MeterRegistry metrics,
      Clock clock) {
    this.publisher = publisher;
    this.props = props;
    this.rateLimiter = rateLimiter;
    this.metrics = metrics;
    this.clock = clock;
  }

  @PostMapping("/v1/events")
  public ResponseEntity<EventBatchResponse> ingest(@RequestBody EventBatchRequest request) {
    if (request == null || request.events() == null || request.events().isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_BATCH", "events must not be empty");
    }
    if (request.events().size() > props.maxBatchSize()) {
      throw new ApiException(
          HttpStatus.PAYLOAD_TOO_LARGE,
          "BATCH_TOO_LARGE",
          "max " + props.maxBatchSize() + " events");
    }
    Instant received = clock.instant();
    List<Rejection> rejected = new ArrayList<>();
    List<Outgoing> outgoing = new ArrayList<>();
    List<Integer> indexes = new ArrayList<>();
    List<EventDto> events = request.events();
    for (int i = 0; i < events.size(); i++) {
      EventDto e = events.get(i);
      String error = EventValidator.validate(e);
      if (error == null && !rateLimiter.tryAcquire(e.userId())) {
        error = "RATE_LIMITED";
      }
      if (error != null) {
        rejected.add(new Rejection(i, e == null ? null : e.eventId(), error));
        count("rejected", error);
        continue;
      }
      UserEvent avro = EventMapper.toAvro(e, received, props.maxClockSkew());
      outgoing.add(new Outgoing(Topics.EVENTS_RAW, avro.getUserId(), avro.getEventTs(), avro));
      indexes.add(i);
    }
    List<Boolean> acked = publisher.publish(outgoing, props.publishTimeout());
    int accepted = 0;
    for (int j = 0; j < acked.size(); j++) {
      if (acked.get(j)) {
        accepted++;
      } else {
        int i = indexes.get(j);
        rejected.add(new Rejection(i, events.get(i).eventId(), "PUBLISH_FAILED"));
        count("rejected", "PUBLISH_FAILED");
      }
    }
    metrics
        .counter("recs_ingest_events_total", "result", "accepted", "reason", "")
        .increment(accepted);
    rejected.sort(java.util.Comparator.comparingInt(Rejection::index));
    boolean allRateLimited =
        accepted == 0
            && !rejected.isEmpty()
            && rejected.stream().allMatch(r -> r.error().equals("RATE_LIMITED"));
    HttpStatus status = allRateLimited ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.ACCEPTED;
    return ResponseEntity.status(status).body(new EventBatchResponse(accepted, rejected));
  }

  private void count(String result, String reason) {
    metrics.counter("recs_ingest_events_total", "result", result, "reason", reason).increment();
  }
}
