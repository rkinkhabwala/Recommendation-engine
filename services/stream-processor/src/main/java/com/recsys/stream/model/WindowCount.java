package com.recsys.stream.model;

/** Play count of one item in one event-time window (absolute value, safe to re-apply). */
public record WindowCount(String domainRegion, String itemId, long windowStart, long count) {}
