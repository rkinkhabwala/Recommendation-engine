package com.recsys.features.model;

/**
 * A stored user vector: per-domain long-term taste ({@code u:{id}:lt:<domain>}), onboarding seed
 * ({@code u:{id}:seed:<domain>}) or cross-domain taste ({@code u:{id}:x}). {@code vector} is unit
 * length, float16 little endian.
 */
public record UserVector(
    String userId, String indexVersion, byte[] vector, String source, long updatedTs) {}
