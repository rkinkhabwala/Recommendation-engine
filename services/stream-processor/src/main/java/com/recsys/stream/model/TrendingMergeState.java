package com.recsys.stream.model;

import java.util.HashMap;
import java.util.Map;

public class TrendingMergeState {
  public long seq;
  public Map<Integer, PartialTop> partials = new HashMap<>();
}
