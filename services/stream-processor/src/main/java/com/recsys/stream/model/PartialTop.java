package com.recsys.stream.model;

import com.recsys.features.model.ScoredItem;
import java.util.List;

/** Stage-1 trending result from one stream task (partition) for one domain+region. */
public record PartialTop(String domainRegion, int partition, List<ScoredItem> items, long wallTs) {}
