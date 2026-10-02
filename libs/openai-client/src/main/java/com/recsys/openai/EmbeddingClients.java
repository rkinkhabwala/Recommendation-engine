package com.recsys.openai;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses the implementation from settings. */
public final class EmbeddingClients {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingClients.class);

  private EmbeddingClients() {}

  public static EmbeddingClient create(OpenAiSettings settings, MeterRegistry registry) {
    CostMeter cost =
        new CostMeter(
            registry,
            settings.pricePerMillionTokens(),
            new BudgetGuard(settings.monthlyBudgetUsd(), Clock.systemUTC()));
    if (settings.mock()) {
      log.info(
          "Using MockEmbeddingClient ({} dims). Set recs.openai.mode=openai to call OpenAI.",
          settings.dimensions());
      return new MockEmbeddingClient(
          "mock-" + settings.embeddingModel(), settings.dimensions(), cost);
    }
    log.info(
        "Using OpenAI embeddings: model={} dims={}",
        settings.embeddingModel(),
        settings.dimensions());
    return new OpenAiEmbeddingClient(settings, cost, registry);
  }
}
