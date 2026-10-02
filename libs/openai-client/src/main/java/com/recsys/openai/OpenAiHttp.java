package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared OpenAI HTTP transport. Resilience, outermost first: retry (exponential backoff with
 * jitter, honours Retry-After) → circuit breaker → client-side rate limiter → HTTP call with
 * timeout. Failures are classified as {@link OpenAiException.Kind}.
 */
public final class OpenAiHttp {
  private static final Logger log = LoggerFactory.getLogger(OpenAiHttp.class);
  static final ObjectMapper JSON = new ObjectMapper();

  private final OpenAiSettings settings;
  private final HttpClient http;
  private final Retry retry;
  private final CircuitBreaker breaker;
  private final RateLimiter limiter;

  public OpenAiHttp(OpenAiSettings settings, String name) {
    if (settings.apiKey() == null || settings.apiKey().isBlank()) {
      throw new IllegalStateException(
          "recs.openai.mode=openai requires OPENAI_API_KEY (env var or secrets manager)");
    }
    this.settings = settings;
    this.http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    this.breaker =
        CircuitBreaker.of(
            "openai-" + name,
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
            "openai-" + name,
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
            "openai-" + name,
            RetryConfig.custom()
                .maxAttempts(settings.maxAttempts())
                .intervalBiFunction(backoff)
                .retryOnException(e -> e instanceof OpenAiException o && o.retryable())
                .build());
    breaker
        .getEventPublisher()
        .onStateTransition(
            e -> log.warn("OpenAI {} circuit breaker {}", name, e.getStateTransition()));
  }

  public CircuitBreaker.State circuitState() {
    return breaker.getState();
  }

  /** POST JSON, parse JSON response, with full resilience. */
  public JsonNode postJson(String path, Object body) {
    byte[] bytes;
    try {
      bytes = JSON.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new OpenAiException(OpenAiException.Kind.BAD_INPUT, "cannot encode request", -1, e);
    }
    return resilient(
        () ->
            parse(
                send(
                    request(path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)))));
  }

  public JsonNode getJson(String path) {
    return resilient(() -> parse(send(request(path).GET())));
  }

  public byte[] getBytes(String path) {
    return resilient(() -> send(request(path).GET()));
  }

  /** multipart/form-data upload (Files API). */
  public JsonNode uploadFile(String purpose, String filename, byte[] content) {
    String boundary = "----recsys" + Long.toHexString(ThreadLocalRandom.current().nextLong());
    byte[] head =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                + purpose
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\nContent-Type: application/jsonl\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
    byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[head.length + content.length + tail.length];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(content, 0, body, head.length, content.length);
    System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
    return resilient(
        () ->
            parse(
                send(
                    request("/files")
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)))));
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create(settings.baseUrl() + path))
        .timeout(settings.timeout())
        .header("Authorization", "Bearer " + settings.apiKey());
  }

  private <T> T resilient(Supplier<T> call) {
    Supplier<T> decorated =
        Retry.decorateSupplier(
            retry,
            CircuitBreaker.decorateSupplier(breaker, RateLimiter.decorateSupplier(limiter, call)));
    try {
      return decorated.get();
    } catch (CallNotPermittedException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "OpenAI circuit open", 30_000, e);
    } catch (RequestNotPermitted e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "client rate limit", 1_000, e);
    }
  }

  private byte[] send(HttpRequest.Builder builder) {
    HttpResponse<byte[]> response;
    try {
      response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpTimeoutException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "timeout", -1, e);
    } catch (IOException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "I/O: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "interrupted", -1, e);
    }
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return response.body();
    }
    String error = new String(response.body(), StandardCharsets.UTF_8);
    error = error.length() > 300 ? error.substring(0, 300) : error;
    if (status == 429 || status >= 500) {
      long retryAfter =
          response.headers().firstValue("retry-after").map(OpenAiHttp::seconds).orElse(-1L);
      throw new OpenAiException(
          OpenAiException.Kind.RETRYABLE, "HTTP " + status + ": " + error, retryAfter, null);
    }
    if (status == 401 || status == 403 || status == 404) {
      throw new OpenAiException(OpenAiException.Kind.CONFIG, "HTTP " + status + ": " + error);
    }
    throw new OpenAiException(OpenAiException.Kind.BAD_INPUT, "HTTP " + status + ": " + error);
  }

  private static JsonNode parse(byte[] body) {
    try {
      return JSON.readTree(body);
    } catch (IOException e) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "bad response body", -1, e);
    }
  }

  private static long seconds(String header) {
    try {
      return (long) (Double.parseDouble(header.trim()) * 1000);
    } catch (NumberFormatException e) {
      return -1;
    }
  }
}
