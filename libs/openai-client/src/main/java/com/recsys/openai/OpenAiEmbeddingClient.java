package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.common.Vectors;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI embeddings over plain HTTPS. Resilience, outermost first: retry (exponential backoff with
 * jitter, honours Retry-After) → circuit breaker → client-side rate limiter → HTTP call with
 * timeout. Only item content and scrubbed text reach this class; never user identifiers.
 */
public final class OpenAiEmbeddingClient implements EmbeddingClient {
  private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final OpenAiSettings settings;
  private final HttpClient http;
  private final CostMeter cost;
  private final MeterRegistry registry;
  private final Retry retry;
  private final CircuitBreaker breaker;
  private final RateLimiter limiter;

  public OpenAiEmbeddingClient(OpenAiSettings settings, CostMeter cost, MeterRegistry registry) {
    if (settings.apiKey() == null || settings.apiKey().isBlank()) {
      throw new IllegalStateException(
          "recs.openai.mode=openai requires OPENAI_API_KEY (env var or secrets manager)");
    }
    this.settings = settings;
    this.cost = cost;
    this.registry = registry;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.breaker =
        CircuitBreaker.of(
            "openai-embeddings",
            CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                // Bad input is the caller's problem, not an outage.
                .ignoreException(
                    e ->
                        e instanceof OpenAiException o
                            && o.kind() == OpenAiException.Kind.BAD_INPUT)
                .build());
    this.limiter =
        RateLimiter.of(
            "openai-embeddings",
            RateLimiterConfig.custom()
                .limitForPeriod(settings.requestsPerMinute())
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ofSeconds(30))
                .build());
    Duration initial = settings.initialBackoff();
    IntervalBiFunction<Object> backoff =
        (attempt, either) -> {
          if (either.isLeft()
              && either.getLeft() instanceof OpenAiException o
              && o.retryAfterMillis() >= 0) {
            return Math.max(o.retryAfterMillis(), initial.toMillis());
          }
          long base = initial.toMillis() * (1L << Math.min(attempt - 1, 6));
          return base / 2 + ThreadLocalRandom.current().nextLong(base / 2 + 1); // jitter
        };
    this.retry =
        Retry.of(
            "openai-embeddings",
            RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalBiFunction(backoff)
                .retryOnException(e -> e instanceof OpenAiException o && o.retryable())
                .build());
    breaker
        .getEventPublisher()
        .onStateTransition(e -> log.warn("OpenAI circuit breaker {}", e.getStateTransition()));
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
    return breaker.getState();
  }

  @Override
  public EmbeddingResponse embed(List<String> inputs, String job, JobPriority priority) {
    if (inputs.isEmpty()) {
      return new EmbeddingResponse(List.of(), 0);
    }
    cost.budget().check(priority);
    Supplier<EmbeddingResponse> call =
        Retry.decorateSupplier(
            retry,
            CircuitBreaker.decorateSupplier(
                breaker, RateLimiter.decorateSupplier(limiter, () -> callOnce(inputs))));
    Timer.Sample sample = Timer.start(registry);
    String outcome = "success";
    try {
      EmbeddingResponse response = call.get();
      cost.record(settings.embeddingModel(), job, response.totalTokens());
      return response;
    } catch (CallNotPermittedException e) {
      outcome = "circuit_open";
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "OpenAI circuit open", 30_000, e);
    } catch (RequestNotPermitted e) {
      outcome = "rate_limited";
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "client rate limit", 1_000, e);
    } catch (OpenAiException e) {
      outcome = e.kind().name().toLowerCase();
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

  private EmbeddingResponse callOnce(List<String> inputs) {
    byte[] body;
    try {
      body =
          JSON.writeValueAsBytes(
              Map.of(
                  "model",
                  settings.embeddingModel(),
                  "input",
                  inputs,
                  "dimensions",
                  settings.dimensions(),
                  "encoding_format",
                  "float"));
    } catch (IOException e) {
      throw new OpenAiException(OpenAiException.Kind.BAD_INPUT, "cannot encode request", -1, e);
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/embeddings"))
            .timeout(settings.timeout())
            .header("Authorization", "Bearer " + settings.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<byte[]> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpTimeoutException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "timeout", -1, e);
    } catch (IOException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "I/O: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "interrupted", -1, e);
    }
    int status = response.statusCode();
    if (status == 200) {
      return parse(response.body(), inputs.size());
    }
    String error = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
    error = error.length() > 300 ? error.substring(0, 300) : error;
    if (status == 429 || status >= 500) {
      long retryAfter =
          response
              .headers()
              .firstValue("retry-after")
              .map(OpenAiEmbeddingClient::seconds)
              .orElse(-1L);
      throw new OpenAiException(
          OpenAiException.Kind.RETRYABLE, "HTTP " + status + ": " + error, retryAfter, null);
    }
    if (status == 401 || status == 403 || status == 404) {
      throw new OpenAiException(OpenAiException.Kind.CONFIG, "HTTP " + status + ": " + error);
    }
    throw new OpenAiException(OpenAiException.Kind.BAD_INPUT, "HTTP " + status + ": " + error);
  }

  private static long seconds(String header) {
    try {
      return (long) (Double.parseDouble(header.trim()) * 1000);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static EmbeddingResponse parse(byte[] body, int expected) {
    try {
      JsonNode root = JSON.readTree(body);
      float[][] out = new float[expected][];
      for (JsonNode item : root.get("data")) {
        JsonNode emb = item.get("embedding");
        float[] v = new float[emb.size()];
        for (int i = 0; i < v.length; i++) {
          v[i] = (float) emb.get(i).asDouble();
        }
        out[item.get("index").asInt()] = Vectors.normalized(v);
      }
      List<float[]> vectors = new ArrayList<>(expected);
      for (float[] v : out) {
        if (v == null) {
          throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "incomplete response");
        }
        vectors.add(v);
      }
      long tokens = root.path("usage").path("total_tokens").asLong(0);
      return new EmbeddingResponse(vectors, tokens);
    } catch (IOException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "bad response body", -1, e);
    }
  }
}
