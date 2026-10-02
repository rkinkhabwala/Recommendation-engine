package com.recsys.features.model;

/**
 * A stored user vector: long-term taste ({@code u:{id}:lt}) or onboarding seed ({@code
 * u:{id}:seed}). {@code vector} is unit length, float16 little endian.
 */
public record UserVector(
    String userId, String indexVersion, byte[] vector, String source, long updatedTs) {}
