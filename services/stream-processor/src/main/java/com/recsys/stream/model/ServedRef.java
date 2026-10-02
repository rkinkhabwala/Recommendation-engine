package com.recsys.stream.model;

/** One item of a served recommendation list (exploded for the attribution join). */
public record ServedRef(
    String recommendationId,
    String itemId,
    int position,
    String userId,
    String variantId,
    boolean explore,
    long servedTs) {}
