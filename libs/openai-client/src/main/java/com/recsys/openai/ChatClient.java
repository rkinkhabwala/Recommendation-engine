package com.recsys.openai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * LLM with structured outputs (JSON-schema constrained). Used only by async workers: metadata
 * enrichment and cached explanations. Never on the serving path.
 */
public interface ChatClient {

  /** A structured-output request. {@code schema} is a strict JSON schema (all fields required). */
  record StructuredRequest(
      String system, String user, String schemaName, JsonNode schema, int maxOutputTokens) {}

  record StructuredResponse(JsonNode json, long inputTokens, long outputTokens) {}

  StructuredResponse complete(StructuredRequest request, String job, JobPriority priority);

  String model();
}
