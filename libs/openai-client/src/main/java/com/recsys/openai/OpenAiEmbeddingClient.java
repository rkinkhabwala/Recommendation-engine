package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.common.Vectors;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI embeddings over HTTPS (resilience in {@link OpenAiHttp}). Only item content and scrubbed
 * text reach this class; never user identifiers.
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {
  private final OpenAiSettings settings;
  private final OpenAiHttp http;
  private final CostMeter cost;
  private final MeterRegistry registry;

  public OpenAiEmbeddingClient(OpenAiSettings settings, CostMeter cost, MeterRegistry registry) {
    this.settings = settings;
    this.http = new OpenAiHttp(settings, "embeddings");
    this.cost = cost;
    this.registry = registry;
  }

  @Override
  public String model() {
    return settings.embeddingModel();
  }

  @Override
  public int dimensions() {
    return settings.dimensions();
  }

  public CircuitBreaker.State circuitState() {
    return http.circuitState();
  }

  @Override
  public EmbeddingResponse embed(List<String> inputs, String job, JobPriority priority) {
    if (inputs.isEmpty()) {
      return new EmbeddingResponse(List.of(), 0);
    }
    cost.budget().check(priority);
    Timer.Sample sample = Timer.start(registry);
    String outcome = "success";
    try {
      JsonNode root =
          http.postJson(
              "/embeddings",
              Map.of(
                  "model",
                  settings.embeddingModel(),
                  "input",
                  inputs,
                  "dimensions",
                  settings.dimensions(),
                  "encoding_format",
                  "float"));
      EmbeddingResponse response = parse(root, inputs.size());
      cost.record(settings.embeddingModel(), job, response.totalTokens());
      return response;
    } catch (OpenAiException e) {
      outcome =
          e.getMessage().contains("circuit open") ? "circuit_open" : e.kind().name().toLowerCase();
      throw e;
    } finally {
      sample.stop(
          Timer.builder("recs_openai_request_seconds")
              .tag("model", settings.embeddingModel())
              .tag("job", job)
              .tag("outcome", outcome)
              .register(registry));
    }
  }

  static EmbeddingResponse parse(JsonNode root, int expected) {
    float[][] out = new float[expected][];
    for (JsonNode item : root.get("data")) {
      out[item.get("index").asInt()] = vector(item.get("embedding"));
    }
    List<float[]> vectors = new ArrayList<>(expected);
    for (float[] v : out) {
      if (v == null) {
        throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "incomplete response");
      }
      vectors.add(v);
    }
    return new EmbeddingResponse(vectors, root.path("usage").path("total_tokens").asLong(0));
  }

  static float[] vector(JsonNode emb) {
    float[] v = new float[emb.size()];
    for (int i = 0; i < v.length; i++) {
      v[i] = (float) emb.get(i).asDouble();
    }
    return Vectors.normalized(v);
  }
}
