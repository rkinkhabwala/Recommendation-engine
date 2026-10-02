package com.recsys.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiEmbeddingClientTest {
  record Reply(int status, String body, String retryAfter) {}

  HttpServer server;
  final Deque<Reply> replies = new ArrayDeque<>();
  final AtomicInteger calls = new AtomicInteger();
  SimpleMeterRegistry registry;
  String lastBody;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        ex -> {
          calls.incrementAndGet();
          lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          Reply r = replies.isEmpty() ? new Reply(500, "{}", null) : replies.poll();
          if (r.retryAfter() != null) {
            ex.getResponseHeaders().add("Retry-After", r.retryAfter());
          }
          byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(r.status(), b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    registry = new SimpleMeterRegistry();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  OpenAiEmbeddingClient client(double budget) {
    var settings =
        new OpenAiSettings(
            "openai",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "sk-test",
            "text-embedding-3-small",
            3,
            Duration.ofSeconds(2),
            4,
            Duration.ofMillis(5),
            1000,
            budget,
            Map.of("text-embedding-3-small", 0.02),
            "gpt-test");
    var cost =
        new CostMeter(
            registry, settings.pricePerMillionTokens(), new BudgetGuard(budget, Clock.systemUTC()));
    return new OpenAiEmbeddingClient(settings, cost, registry);
  }

  static String ok(int tokens) {
    return """
        {"data":[{"index":1,"embedding":[0,3,4]},{"index":0,"embedding":[1,0,0]}],
         "usage":{"prompt_tokens":%d,"total_tokens":%d}}"""
        .formatted(tokens, tokens);
  }

  @Test
  void retriesOn429HonouringRetryAfterThenSucceeds() {
    replies.add(new Reply(429, "{\"error\":\"rate\"}", "0"));
    replies.add(new Reply(503, "{}", null));
    replies.add(new Reply(200, ok(1_000_000), null));

    var res = client(0).embed(List.of("a", "b"), "catalog", JobPriority.ESSENTIAL);

    assertThat(calls).hasValue(3);
    assertThat(res.vectors().get(0)).containsExactly(1, 0, 0);
    assertThat(res.vectors().get(1)).containsExactly(0, 0.6f, 0.8f); // normalized, index order
    assertThat(lastBody).contains("\"dimensions\":3").contains("text-embedding-3-small");
    assertThat(
            registry
                .counter(
                    "recs_openai_cost_usd_total",
                    "model",
                    "text-embedding-3-small",
                    "job",
                    "catalog")
                .count())
        .isEqualTo(0.02);
  }

  @Test
  void badInputIsNotRetried() {
    replies.add(new Reply(400, "{\"error\":\"too long\"}", null));
    assertThatThrownBy(() -> client(0).embed(List.of("x"), "catalog", JobPriority.ESSENTIAL))
        .isInstanceOfSatisfying(
            OpenAiException.class,
            e -> assertThat(e.kind()).isEqualTo(OpenAiException.Kind.BAD_INPUT));
    assertThat(calls).hasValue(1);
  }

  @Test
  void authFailureIsConfigErrorNotPoison() {
    replies.add(new Reply(401, "{}", null));
    assertThatThrownBy(() -> client(0).embed(List.of("x"), "catalog", JobPriority.ESSENTIAL))
        .isInstanceOfSatisfying(
            OpenAiException.class,
            e -> assertThat(e.kind()).isEqualTo(OpenAiException.Kind.CONFIG));
  }

  @Test
  void persistentOutageSurfacesAsRetryable() {
    var c = client(0);
    assertThatThrownBy(() -> c.embed(List.of("x"), "catalog", JobPriority.ESSENTIAL))
        .isInstanceOfSatisfying(OpenAiException.class, e -> assertThat(e.retryable()).isTrue());
    assertThat(calls).hasValue(4); // maxAttempts
  }

  @Test
  void budgetPausesNonEssentialJobsOnly() {
    replies.add(new Reply(200, ok(1_000_000), null)); // $0.02 of a $0.02 budget
    replies.add(new Reply(200, ok(10), null));
    var c = client(0.02);
    c.embed(List.of("a", "b"), "catalog", JobPriority.ESSENTIAL);

    assertThatThrownBy(() -> c.embed(List.of("a", "b"), "backfill", JobPriority.NON_ESSENTIAL))
        .isInstanceOfSatisfying(
            OpenAiException.class,
            e -> assertThat(e.kind()).isEqualTo(OpenAiException.Kind.BUDGET));
    assertThat(c.embed(List.of("a", "b"), "catalog", JobPriority.ESSENTIAL).vectors()).hasSize(2);
  }

  @Test
  void requiresApiKey() {
    var settings =
        new OpenAiSettings("openai", null, "", "m", 3, null, 0, null, 0, 0, Map.of(), null);
    assertThatThrownBy(() -> new OpenAiEmbeddingClient(settings, null, registry))
        .hasMessageContaining("OPENAI_API_KEY");
  }
}
