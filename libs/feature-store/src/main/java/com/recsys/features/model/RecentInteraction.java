package com.recsys.features.model;

public record RecentInteraction(
    String itemId, String artistId, String eventType, double weight, long ts) {}
