package com.recsys.enrichment;

import com.recsys.events.AvroTrust;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Async LLM jobs (docs/architecture.md §10): metadata enrichment of catalog items and cached,
 * user-agnostic "why this" explanations. Never on the serving path; non-essential for the budget
 * guard (pauses at 100% of the monthly budget).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EnrichmentWorkerApplication {
  public static void main(String[] args) {
    AvroTrust.install(); // Avro 1.12 only (de)serializes trusted SpecificRecord classes
    SpringApplication.run(EnrichmentWorkerApplication.class, args);
  }
}
