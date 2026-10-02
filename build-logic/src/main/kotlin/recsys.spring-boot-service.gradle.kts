plugins {
  id("recsys.java-conventions")
  id("org.springframework.boot")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  // Trace/span ids in logs and W3C traceparent propagation (HTTP + Kafka headers).
  // TODO(phase-3): export spans via OTLP to a collector.
  implementation("io.micrometer:micrometer-tracing-bridge-otel")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Only the fat jar is needed for docker images.
tasks.named<Jar>("jar") { enabled = false }
