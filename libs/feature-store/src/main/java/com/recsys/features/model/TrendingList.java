package com.recsys.features.model;

import java.util.List;

public record TrendingList(String domain, String region, List<ScoredItem> items, long updatedTs) {}
