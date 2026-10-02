package com.recsys.api.core;

import com.recsys.api.candidates.ScoredCandidate;
import java.time.Instant;
import java.util.List;

public record RecommendationResult(
    String recommendationId,
    String variantId,
    String rankerVersion,
    String indexVersion,
    FallbackLevel fallbackLevel,
    Instant generatedAt,
    List<ScoredCandidate> items) {}
