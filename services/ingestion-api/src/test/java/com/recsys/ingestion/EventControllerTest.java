package com.recsys.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.recsys.ingestion.kafka.EventPublisher;
import com.recsys.ingestion.ratelimit.UserRateLimiter;
import com.recsys.ingestion.web.EventController;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
@ImportAutoConfiguration(com.recsys.web.WebSupportAutoConfiguration.class)
@TestPropertySource(properties = "recs.security.api-keys=k1")
class EventControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean EventPublisher publisher;

  @TestConfiguration
  static class Config {
    @Bean
    IngestionProperties props() {
      return new IngestionProperties(
          500, Duration.ofSeconds(1), Duration.ofSeconds(60), 100, 100, 500);
    }

    @Bean
    UserRateLimiter limiter() {
      return new UserRateLimiter(100, 100);
    }

    @Bean
    io.micrometer.core.instrument.MeterRegistry meterRegistry() {
      return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  static final String BODY =
      """
      {"events":[
        {"eventId":"01929a7e-1234-7abc-8def-0123456789ab","userId":"u_1","itemId":"s_1","domain":"song",
         "eventType":"skip","value":7.2,"eventTs":"2026-10-02T09:15:03.120Z","sessionId":"sess_9",
         "context":{"device":"ios","country":"US"},"media":{"positionMs":7200,"durationMs":214000}},
        {"eventId":"not-a-uuid","userId":"u_1","itemId":"s_1","domain":"song","eventType":"skip",
         "eventTs":"2026-10-02T09:15:03Z","sessionId":"s"}
      ]}""";

  @Test
  void acceptsValidRejectsInvalidPartially() throws Exception {
    when(publisher.publish(anyList(), any())).thenReturn(List.of(true));
    mvc.perform(
            post("/v1/events")
                .header("X-Api-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(1))
        .andExpect(jsonPath("$.rejected[0].index").value(1))
        .andExpect(jsonPath("$.rejected[0].error").value("INVALID_EVENT_ID"));
  }

  @Test
  void unackedEventsAreReportedSoClientRetries() throws Exception {
    when(publisher.publish(anyList(), any())).thenReturn(List.of(false));
    mvc.perform(
            post("/v1/events")
                .header("X-Api-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(0))
        .andExpect(jsonPath("$.rejected[0].error").value("PUBLISH_FAILED"));
  }

  @Test
  void requiresApiKey() throws Exception {
    mvc.perform(post("/v1/events").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }
}
