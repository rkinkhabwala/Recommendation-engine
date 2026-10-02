package com.recsys.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatAndBatchClientTest {
  final ObjectMapper json = new ObjectMapper();
  HttpServer server;
  final Map<String, String> replies = new ConcurrentHashMap<>();
  final Map<String, String> requests = new ConcurrentHashMap<>();
  SimpleMeterRegistry registry;
  OpenAiSettings settings;
  CostMeter cost;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          String path = ex.getRequestURI().getPath();
          requests.put(
              ex.getRequestMethod() + " " + path,
              new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String body = replies.getOrDefault(ex.getRequestMethod() + " " + path, "{}");
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(
              replies.containsKey(ex.getRequestMethod() + " " + path) ? 200 : 404, b.length);
          ex.getResponseBody().write(b);
          ex.close();
        });
    server.start();
    registry = new SimpleMeterRegistry();
    settings =
        new OpenAiSettings(
            "openai",
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "sk-test",
            "text-embedding-3-small",
            3,
            Duration.ofSeconds(2),
            2,
            Duration.ofMillis(5),
            1000,
            0,
            Map.of("gpt-test", 1.0, "gpt-test:output", 4.0, "text-embedding-3-small", 0.02),
            "gpt-test");
    cost =
        new CostMeter(
            registry, settings.pricePerMillionTokens(), new BudgetGuard(0, Clock.systemUTC()));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void structuredOutputIsRequestedStrictlyAndParsed() throws Exception {
    replies.put(
        "POST /v1/chat/completions",
        """
        {"choices":[{"message":{"content":"{\\"moods\\":[\\"calm\\"]}"}}],
         "usage":{"prompt_tokens":1000000,"completion_tokens":1000000}}""");
    var client = new OpenAiChatClient(settings, cost, registry);
    var res =
        client.complete(
            new ChatClient.StructuredRequest(
                "sys", "user", "item_enrichment", json.readTree("{\"type\":\"object\"}"), 200),
            "enrichment",
            JobPriority.NON_ESSENTIAL);

    assertThat(res.json().get("moods").get(0).asText()).isEqualTo("calm");
    var sent = json.readTree(requests.get("POST /v1/chat/completions"));
    assertThat(sent.path("response_format").path("json_schema").path("strict").asBoolean())
        .isTrue();
    assertThat(sent.path("model").asText()).isEqualTo("gpt-test");
    // $1 input + $4 output per 1M tokens
    double usd =
        registry.find("recs_openai_cost_usd_total").counters().stream()
            .mapToDouble(c -> c.count())
            .sum();
    assertThat(usd).isEqualTo(5.0);
  }

  @Test
  void refusalIsBadInput() {
    replies.put(
        "POST /v1/chat/completions",
        "{\"choices\":[{\"message\":{\"refusal\":\"no\",\"content\":null}}]}");
    var client = new OpenAiChatClient(settings, cost, registry);
    assertThatThrownBy(
            () ->
                client.complete(
                    new ChatClient.StructuredRequest("s", "u", "x", json.createObjectNode(), 10),
                    "j",
                    JobPriority.NON_ESSENTIAL))
        .isInstanceOfSatisfying(
            OpenAiException.class,
            e -> assertThat(e.kind()).isEqualTo(OpenAiException.Kind.BAD_INPUT));
  }

  @Test
  void batchEmbeddingsRoundTrip() throws Exception {
    replies.put("POST /v1/files", "{\"id\":\"file-in\"}");
    replies.put("POST /v1/batches", "{\"id\":\"batch-1\",\"status\":\"validating\"}");
    replies.put(
        "GET /v1/batches/batch-1",
        "{\"id\":\"batch-1\",\"status\":\"completed\",\"output_file_id\":\"file-out\",\"request_counts\":{\"completed\":2,\"failed\":0}}");
    replies.put(
        "GET /v1/files/file-out/content",
        """
        {"custom_id":"s_1","response":{"status_code":200,"body":{"data":[{"embedding":[3,4,0]}],"usage":{"total_tokens":5}}}}
        {"custom_id":"s_2","response":{"status_code":400,"body":{}}}
        """);
    var batch = new OpenAiBatchClient(settings, cost);

    String id =
        batch.submitEmbeddings(
            List.of(
                new OpenAiBatchClient.BatchInput("s_1", "a"),
                new OpenAiBatchClient.BatchInput("s_2", "b")),
            JobPriority.NON_ESSENTIAL);
    assertThat(id).isEqualTo("batch-1");
    assertThat(requests.get("POST /v1/files"))
        .contains("name=\"purpose\"")
        .contains("batch")
        .contains("\"custom_id\":\"s_2\"")
        .contains("\"dimensions\":3");
    assertThat(json.readTree(requests.get("POST /v1/batches")).get("endpoint").asText())
        .isEqualTo("/v1/embeddings");
    var status = batch.status(id);
    assertThat(status.done()).isTrue();
    var vectors = batch.results(status.outputFileId(), "backfill");
    assertThat(vectors).containsOnlyKeys("s_1");
    assertThat(vectors.get("s_1")).containsExactly(0.6f, 0.8f, 0f);
  }
}
