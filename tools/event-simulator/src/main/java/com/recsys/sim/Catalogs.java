package com.recsys.sim;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One synthetic catalog per domain; ids are prefixed by domain (s_, b_, v_, p_). */
public final class Catalogs {
  private final Map<String, SyntheticCatalog> byDomain = new LinkedHashMap<>();

  public Catalogs(Map<String, Integer> sizes, long seed, LocalDate today) {
    sizes.forEach((d, n) -> byDomain.put(d, SyntheticCatalog.forDomain(d, n, seed, today)));
  }

  public SyntheticCatalog domain(String d) {
    return byDomain.get(d);
  }

  public List<String> domains() {
    return List.copyOf(byDomain.keySet());
  }

  public Item get(String id) {
    for (SyntheticCatalog c : byDomain.values()) {
      Item i = c.get(id);
      if (i != null) {
        return i;
      }
    }
    return null;
  }
}
