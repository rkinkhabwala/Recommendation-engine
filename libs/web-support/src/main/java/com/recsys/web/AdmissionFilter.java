package com.recsys.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Load shedding for /v1/**: at most {@code maxInFlight} requests run at once; a request waits up to
 * {@code queueTimeout} for a slot, then gets 503 + {@code Retry-After: 1}.
 *
 * <p>With virtual threads Tomcat no longer caps concurrency, so without this an overloaded pod
 * queues unboundedly: latency climbs for everyone and memory grows until the container is
 * OOM-killed (seen in the kind capacity ramp). Shedding keeps admitted requests inside the SLO and
 * lets the gateway retry on another replica while the HPA adds pods.
 */
public class AdmissionFilter extends OncePerRequestFilter {
  private final Semaphore slots;
  private final int maxInFlight;
  private final long queueNanos;
  private final Counter rejected;

  public AdmissionFilter(int maxInFlight, Duration queueTimeout, MeterRegistry registry) {
    this.maxInFlight = maxInFlight;
    this.slots = new Semaphore(maxInFlight);
    this.queueNanos = queueTimeout.toNanos();
    this.rejected =
        Counter.builder("recs_http_admission_rejected")
            .description("Requests shed with 503 because max in-flight was reached")
            .register(registry);
    Gauge.builder("recs_http_in_flight", this, f -> f.maxInFlight - f.slots.availablePermits())
        .description("Requests currently admitted under /v1")
        .register(registry);
    Gauge.builder("recs_http_max_in_flight", this, f -> f.maxInFlight).register(registry);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    boolean admitted;
    try {
      admitted = slots.tryAcquire(queueNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      admitted = false;
    }
    if (!admitted) {
      rejected.increment();
      response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      response.setHeader("Retry-After", "1");
      response.setContentType("application/json");
      response.getWriter().write("{\"error\":\"OVERLOADED\",\"message\":\"retry shortly\"}");
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      slots.release();
    }
  }
}
