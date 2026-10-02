package com.recsys.features.model;

/** A cached, user-agnostic "why this" sentence generated offline by the LLM worker. */
public record Explanation(String text, String model, long createdTs) {}
