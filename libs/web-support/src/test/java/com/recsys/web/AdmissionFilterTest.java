package com.recsys.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdmissionFilterTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AdmissionFilter filter = new AdmissionFilter(1, Duration.ofMillis(10), registry);

  @Test
  void shedsWhenAllSlotsAreBusyAndAdmitsAgainAfterRelease() throws Exception {
    var inside = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    Thread holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    filter.doFilter(
                        request(),
                        new MockHttpServletResponse(),
                        (req, res) -> {
                          inside.countDown();
                          try {
                            release.await();
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        });
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
    assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(registry.get("recs_http_in_flight").gauge().value()).isEqualTo(1.0);

    var shed = new MockHttpServletResponse();
    filter.doFilter(request(), shed, (req, res) -> {});
    assertThat(shed.getStatus()).isEqualTo(503);
    assertThat(shed.getHeader("Retry-After")).isEqualTo("1");
    assertThat(registry.get("recs_http_admission_rejected").counter().count()).isEqualTo(1.0);

    release.countDown();
    holder.join();
    var ok = new MockHttpServletResponse();
    filter.doFilter(request(), ok, (req, res) -> {});
    assertThat(ok.getStatus()).isEqualTo(200);
    assertThat(registry.get("recs_http_in_flight").gauge().value()).isEqualTo(0.0);
  }

  @Test
  void actuatorIsNeverShed() throws Exception {
    var req = new MockHttpServletRequest("GET", "/actuator/health/readiness");
    assertThat(filter.shouldNotFilter(req)).isTrue();
  }

  private static MockHttpServletRequest request() {
    return new MockHttpServletRequest("GET", "/v1/recommendations");
  }
}
