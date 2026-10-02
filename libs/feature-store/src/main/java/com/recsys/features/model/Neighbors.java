package com.recsys.features.model;

import java.util.List;

/** Top-K related items (item-item co-engagement or session next-item), scores in [0,1]. */
public record Neighbors(String itemId, String kind, List<ScoredItem> items, long updatedTs) {}
