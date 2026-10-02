package com.recsys.openai;

import java.util.List;

/**
 * Text embedding provider. Implementations: {@link OpenAiEmbeddingClient} and the deterministic
 * {@link MockEmbeddingClient} (default; local runs and tests need no API key).
 *
 * <p>Never used on the serving path: only async workers depend on this module.
 */
public interface EmbeddingClient {

  /**
   * Embeds {@code inputs} (order preserved). Vectors are unit length with {@link #dimensions()}
   * dims.
   *
   * @param job metrics label, e.g. "catalog", "onboarding", "backfill"
   * @throws OpenAiException classified as retryable, bad input, or configuration error
   */
  EmbeddingResponse embed(List<String> inputs, String job, JobPriority priority);

  String model();

  int dimensions();
}
