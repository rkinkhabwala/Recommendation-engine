package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Batch API for embedding backfills (cheaper, asynchronous, 24 h completion window): upload
 * a JSONL file of /v1/embeddings requests, create a batch, poll, download results.
 */
public final class OpenAiBatchClient {
  public record BatchInput(String customId, String text) {}

  public record BatchStatus(
      String id,
      String status,
      String outputFileId,
      String errorFileId,
      long completed,
      long failed) {
    public boolean done() {
      return status.equals("completed")
          || status.equals("failed")
          || status.equals("expired")
          || status.equals("cancelled");
    }
  }

  private final OpenAiSettings settings;
  private final OpenAiHttp http;
  private final CostMeter cost;

  public OpenAiBatchClient(OpenAiSettings settings, CostMeter cost) {
    this.settings = settings;
    this.http = new OpenAiHttp(settings, "batch");
    this.cost = cost;
  }

  /**
   * @return batch id
   */
  public String submitEmbeddings(List<BatchInput> inputs, JobPriority priority) {
    cost.budget().check(priority);
    StringBuilder jsonl = new StringBuilder();
    try {
      for (BatchInput in : inputs) {
        jsonl
            .append(
                OpenAiHttp.JSON.writeValueAsString(
                    Map.of(
                        "custom_id",
                        in.customId(),
                        "method",
                        "POST",
                        "url",
                        "/v1/embeddings",
                        "body",
                        Map.of(
                            "model", settings.embeddingModel(),
                            "input", in.text(),
                            "dimensions", settings.dimensions(),
                            "encoding_format", "float"))))
            .append('\n');
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JsonNode file =
        http.uploadFile(
            "batch", "embeddings.jsonl", jsonl.toString().getBytes(StandardCharsets.UTF_8));
    JsonNode batch =
        http.postJson(
            "/batches",
            Map.of(
                "input_file_id",
                file.get("id").asText(),
                "endpoint",
                "/v1/embeddings",
                "completion_window",
                "24h"));
    return batch.get("id").asText();
  }

  public BatchStatus status(String batchId) {
    JsonNode b = http.getJson("/batches/" + batchId);
    JsonNode counts = b.path("request_counts");
    return new BatchStatus(
        b.get("id").asText(),
        b.get("status").asText(),
        b.path("output_file_id").asText(null),
        b.path("error_file_id").asText(null),
        counts.path("completed").asLong(0),
        counts.path("failed").asLong(0));
  }

  /** customId → unit vector for successful lines; failed lines are absent. */
  public Map<String, float[]> results(String outputFileId, String job) {
    byte[] content = http.getBytes("/files/" + outputFileId + "/content");
    Map<String, float[]> out = new HashMap<>();
    long tokens = 0;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        JsonNode row = OpenAiHttp.JSON.readTree(line);
        JsonNode response = row.path("response");
        if (response.path("status_code").asInt() != 200) {
          continue;
        }
        JsonNode body = response.path("body");
        out.put(
            row.get("custom_id").asText(),
            OpenAiEmbeddingClient.vector(body.path("data").path(0).path("embedding")));
        tokens += body.path("usage").path("total_tokens").asLong(0);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    // Batch API pricing is discounted; record at list price into the "batch" job (conservative).
    cost.record(settings.embeddingModel(), job, tokens);
    return out;
  }
}
