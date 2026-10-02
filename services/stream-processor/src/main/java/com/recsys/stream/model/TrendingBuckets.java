package com.recsys.stream.model;

import java.util.TreeMap;

/** Recent window counts for one (domain, region, item). */
public class TrendingBuckets {
  public TreeMap<Long, Long> counts = new TreeMap<>();
}
