package com.recsys.vector;

/** A search result. {@code payload} may be null when payload was not requested. */
public record VectorHit(String itemId, float score, ItemPayload payload) {}
