package com.recsys.openai;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses the implementation from settings. */
public final class EmbeddingClients {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingClients.class);

  private EmbeddingClients() {}

  /** One cost meter (and budget) per process, shared by every OpenAI client. */
  public static CostMeter costMeter(OpenAiSettings settings, MeterRegistry registry) {
    return costMeter(settings, registry, new SpendLedger.InMemory());
  }

  /**
   * @param ledger shared across replicas in production ({@link RedisSpendLedger})
   */
  public static CostMeter costMeter(
      OpenAiSettings settings, MeterRegistry registry, SpendLedger ledger) {
    return new CostMeter(
        registry,
        settings.pricePerMillionTokens(),
        new BudgetGuard(settings.monthlyBudgetUsd(), Clock.systemUTC(), ledger));
  }

  /** Redis-backed ledger when {@code uri} is set, else in-process (dev/tests). */
  public static SpendLedger ledger(String uri) {
    if (uri == null || uri.isBlank()) {
      return new SpendLedger.InMemory();
    }
    var client = io.lettuce.core.RedisClient.create(uri);
    return new RedisSpendLedger(client.connect());
  }

  public static EmbeddingClient create(OpenAiSettings settings, MeterRegistry registry) {
    return create(settings, registry, costMeter(settings, registry));
  }

  public static EmbeddingClient create(
      OpenAiSettings settings, MeterRegistry registry, CostMeter cost) {
    if (settings.mock()) {
      log.info(
          "Using MockEmbeddingClient ({} dims). Set recs.openai.mode=openai to call OpenAI.",
          settings.dimensions());
      return new MockEmbeddingClient(
          MockEmbeddingClient.REVISION + "-" + settings.embeddingModel(),
          settings.dimensions(),
          cost);
    }
    log.info(
        "Using OpenAI embeddings: model={} dims={}",
        settings.embeddingModel(),
        settings.dimensions());
    return new OpenAiEmbeddingClient(settings, cost, registry);
  }

  /**
   * @param mockHandlers schema name → deterministic answer builder, used when mode=mock
   */
  public static ChatClient chat(
      OpenAiSettings settings,
      MeterRegistry registry,
      CostMeter cost,
      java.util.Map<
              String,
              java.util.function.Function<
                  ChatClient.StructuredRequest, com.fasterxml.jackson.databind.JsonNode>>
          mockHandlers) {
    if (settings.mock()) {
      log.info("Using MockChatClient (deterministic). Set recs.openai.mode=openai to call OpenAI.");
      return new MockChatClient(mockHandlers, cost);
    }
    log.info("Using OpenAI chat model {}", settings.chatModel());
    return new OpenAiChatClient(settings, cost, registry);
  }
}
