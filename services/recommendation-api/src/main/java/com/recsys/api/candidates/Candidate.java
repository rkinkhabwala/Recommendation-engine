package com.recsys.api.candidates;

import com.recsys.api.core.ReasonCode;
import com.recsys.vector.ItemPayload;

/**
 * One generator's proposal. {@code score} is the source-specific score in [0,1]; {@code seedItemId}
 * explains reasons like LISTENED_TOGETHER; {@code annScore}/{@code payload} come from vector
 * search.
 */
public record Candidate(
    String itemId,
    String source,
    double score,
    ReasonCode reason,
    String seedItemId,
    Float annScore,
    ItemPayload payload) {}
