package com.recsys.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code recs.admission.*}: {@code max-in-flight} concurrent /v1 requests per pod (0 disables
 * shedding) and how long a request may wait for a slot. Size from the load test: in-flight ≈
 * per-pod capacity (req/s) × target latency, plus headroom for I/O waits.
 */
@ConfigurationProperties("recs.admission")
public record AdmissionSettings(int maxInFlight, Duration queueTimeout) {
  public AdmissionSettings {
    queueTimeout = queueTimeout == null ? Duration.ofMillis(20) : queueTimeout;
  }
}
