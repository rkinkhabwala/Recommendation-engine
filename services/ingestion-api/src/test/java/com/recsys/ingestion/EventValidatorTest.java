package com.recsys.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.recsys.events.v1.EventType;
import com.recsys.ingestion.web.EventDtos.ContextDto;
import com.recsys.ingestion.web.EventDtos.EventDto;
import com.recsys.ingestion.web.EventDtos.MediaDto;
import com.recsys.ingestion.web.EventMapper;
import com.recsys.ingestion.web.EventValidator;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventValidatorTest {
  static final String ID = "01929a7e-1234-7abc-8def-0123456789ab";

  static EventDto event(String type, String itemId, Instant ts) {
    return new EventDto(
        ID,
        "u_1",
        itemId,
        "song",
        type,
        7.2,
        ts,
        "sess_1",
        new ContextDto("ios", "1.0", "US", -420, "next_track", null, true),
        new MediaDto(7200L, 214000L, null, null),
        "rec_1",
        0,
        "control",
        null);
  }

  @Test
  void acceptsValidSkip() {
    assertThat(EventValidator.validate(event("skip", "s_1", Instant.now()))).isNull();
  }

  @Test
  void rejectsBadIdsAndTypes() {
    assertThat(EventValidator.validate(event("teleport", "s_1", Instant.now())))
        .isEqualTo("INVALID_EVENT_TYPE");
    assertThat(EventValidator.validate(event("play_start", null, Instant.now())))
        .isEqualTo("INVALID_ITEM_ID");
    assertThat(EventValidator.validate(event("play_start", "s_{1}", Instant.now())))
        .isEqualTo("INVALID_ITEM_ID"); // braces would break Redis hash tags
    assertThat(EventValidator.validate(event("search", null, Instant.now()))).isNull();
  }

  @Test
  void mapsToAvroAndClampsFutureTimestamps() {
    Instant received = Instant.parse("2026-10-02T10:00:00Z");
    var avro =
        EventMapper.toAvro(
            event("skip", "s_1", received.plusSeconds(3600)), received, Duration.ofSeconds(60));
    assertThat(avro.getEventTs()).isEqualTo(received);
    assertThat(avro.getEventType()).isEqualTo(EventType.SKIP);
    assertThat(avro.getContext().getCountry()).isEqualTo("US");
    assertThat(avro.getMedia().getPositionMs()).isEqualTo(7200L);

    var onTime =
        EventMapper.toAvro(
            event("skip", "s_1", received.minusSeconds(5)), received, Duration.ofSeconds(60));
    assertThat(onTime.getEventTs()).isEqualTo(received.minusSeconds(5));
  }
}
