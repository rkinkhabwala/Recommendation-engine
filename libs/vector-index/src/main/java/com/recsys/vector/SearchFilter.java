package com.recsys.vector;

import java.util.List;

/**
 * Serving-time filters pushed down into the ANN search.
 *
 * @param region ISO country or null for no region filter
 * @param ingestedAfter epoch millis or null; used to retrieve brand-new items
 */
public record SearchFilter(
    String domain,
    String region,
    boolean allowExplicit,
    Long ingestedAfter,
    List<String> excludeIds) {

  public SearchFilter {
    excludeIds = excludeIds == null ? List.of() : List.copyOf(excludeIds);
  }

  public static SearchFilter domain(String domain) {
    return new SearchFilter(domain, null, true, null, List.of());
  }
}
