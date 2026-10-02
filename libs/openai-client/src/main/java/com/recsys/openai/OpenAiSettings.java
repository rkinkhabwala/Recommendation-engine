package com.recsys.openai;

import java.time.Duration;
import java.util.Map;

/**
 * Client settings. Model names, dimensions and prices are configuration, never literals in code.
 *
 * @param mode "mock" (default) or "openai"
 * @param apiKey from env/secrets manager only; required when mode=openai
 * @param pricePerMillionTokens USD per 1M input tokens by model, for cost metrics and budget
 * @param monthlyBudgetUsd 0 disables the budget guard
 */
public record OpenAiSettings(
    String mode,
    String baseUrl,
    String apiKey,
    String embeddingModel,
    int dimensions,
    Duration timeout,
    int maxAttempts,
    Duration initialBackoff,
    int requestsPerMinute,
    double monthlyBudgetUsd,
    Map<String, Double> pricePerMillionTokens) {

  public OpenAiSettings {
    if (mode == null || mode.isBlank()) {
      mode = "mock";
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "https://api.openai.com/v1";
    }
    if (timeout == null) {
      timeout = Duration.ofSeconds(10);
    }
    if (initialBackoff == null) {
      initialBackoff = Duration.ofMillis(500);
    }
    if (maxAttempts <= 0) {
      maxAttempts = 5;
    }
    if (requestsPerMinute <= 0) {
      requestsPerMinute = 500;
    }
    if (dimensions <= 0) {
      throw new IllegalArgumentException("embedding dimensions must be configured");
    }
    if (embeddingModel == null || embeddingModel.isBlank()) {
      throw new IllegalArgumentException("embedding model must be configured");
    }
    pricePerMillionTokens =
        pricePerMillionTokens == null ? Map.of() : Map.copyOf(pricePerMillionTokens);
  }

  public boolean mock() {
    return "mock".equalsIgnoreCase(mode);
  }
}
