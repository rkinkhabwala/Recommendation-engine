package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic offline LLM: each schema name maps to a handler that builds a schema-valid answer
 * from the request (e.g. keyword lexicons for enrichment, templates for explanations). Lets the
 * whole async pipeline run locally and in tests without an API key.
 */
public final class MockChatClient implements ChatClient {
  private final Map<String, Function<StructuredRequest, JsonNode>> handlers;
  private final CostMeter cost;

  public MockChatClient(
      Map<String, Function<StructuredRequest, JsonNode>> handlers, CostMeter cost) {
    this.handlers = Map.copyOf(handlers);
    this.cost = cost;
  }

  @Override
  public StructuredResponse complete(StructuredRequest request, String job, JobPriority priority) {
    var handler = handlers.get(request.schemaName());
    if (handler == null) {
      throw new OpenAiException(
          OpenAiException.Kind.BAD_INPUT, "mock has no handler for " + request.schemaName());
    }
    long in = (request.system().length() + request.user().length()) / 4;
    JsonNode json = handler.apply(request);
    long out = json.toString().length() / 4;
    if (cost != null) {
      cost.recordChat(model(), job, in, out);
    }
    return new StructuredResponse(json, in, out);
  }

  @Override
  public String model() {
    return "mock-llm";
  }
}
