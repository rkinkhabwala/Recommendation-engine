package com.recsys.openai;

import java.util.List;

public record EmbeddingResponse(List<float[]> vectors, long totalTokens) {}
