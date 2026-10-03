plugins {
  id("recsys.java-conventions")
  id("org.springframework.boot")
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("io.micrometer:micrometer-registry-prometheus")
  // Trace/span ids in logs, W3C traceparent propagation (HTTP + Kafka headers) and OTLP export
  // (enabled when MANAGEMENT_OTLP_TRACING_ENDPOINT is set: Jaeger locally, an OTel collector in K8s).
  implementation("io.micrometer:micrometer-tracing-bridge-otel")
  implementation("io.opentelemetry:opentelemetry-exporter-otlp")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Only the fat jar is needed for docker images.
tasks.named<Jar>("jar") { enabled = false }
