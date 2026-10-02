package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Chat Completions with {@code response_format: json_schema} (strict). The model name is config.
 * The response content is parsed as JSON; callers still validate it against their own rules (enums,
 * lengths) before storing anything.
 */
public final class OpenAiChatClient implements ChatClient {
  private final OpenAiSettings settings;
  private final OpenAiHttp http;
  private final CostMeter cost;
  private final MeterRegistry registry;

  public OpenAiChatClient(OpenAiSettings settings, CostMeter cost, MeterRegistry registry) {
    this.settings = settings;
    this.http = new OpenAiHttp(settings, "chat");
    this.cost = cost;
    this.registry = registry;
  }

  @Override
  public String model() {
    return settings.chatModel();
  }

  @Override
  public StructuredResponse complete(StructuredRequest req, String job, JobPriority priority) {
    cost.budget().check(priority);
    Timer.Sample sample = Timer.start(registry);
    String outcome = "success";
    try {
      JsonNode root =
          http.postJson(
              "/chat/completions",
              Map.of(
                  "model", settings.chatModel(),
                  "messages",
                      List.of(
                          Map.of("role", "system", "content", req.system()),
                          Map.of("role", "user", "content", req.user())),
                  "response_format",
                      Map.of(
                          "type",
                          "json_schema",
                          "json_schema",
                          Map.of("name", req.schemaName(), "strict", true, "schema", req.schema())),
                  "max_completion_tokens", req.maxOutputTokens()));
      StructuredResponse response = parse(root);
      cost.recordChat(settings.chatModel(), job, response.inputTokens(), response.outputTokens());
      return response;
    } catch (OpenAiException e) {
      outcome = e.kind().name().toLowerCase();
      throw e;
    } finally {
      sample.stop(
          Timer.builder("recs_openai_request_seconds")
              .tag("model", settings.chatModel())
              .tag("job", job)
              .tag("outcome", outcome)
              .register(registry));
    }
  }

  static StructuredResponse parse(JsonNode root) {
    JsonNode message = root.path("choices").path(0).path("message");
    if (message.hasNonNull("refusal") && !message.get("refusal").asText().isBlank()) {
      throw new OpenAiException(
          OpenAiException.Kind.BAD_INPUT, "model refused: " + message.get("refusal").asText());
    }
    String content = message.path("content").asText(null);
    if (content == null || content.isBlank()) {
      throw new OpenAiException(OpenAiException.Kind.RETRYABLE, "empty completion");
    }
    try {
      JsonNode json = OpenAiHttp.JSON.readTree(content);
      JsonNode usage = root.path("usage");
      return new StructuredResponse(
          json, usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
    } catch (IOException e) {
      // Strict structured outputs should never produce invalid JSON; treat as transient.
      throw new OpenAiException(
          OpenAiException.Kind.RETRYABLE, "completion is not valid JSON", -1, e);
    }
  }
}
