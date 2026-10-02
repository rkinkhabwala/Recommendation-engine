package com.recsys.api.experiment;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.config.ApiProperties.Variant;
import java.util.HashMap;
import java.util.Map;

/** Per-domain experiment assignment (one {@link Bucketer} per domain, each with its own salt). */
public final class Experiments {
  public record Assignment(String domain, String variantId, int bucket, String ranker) {}

  private final Map<String, Bucketer> byDomain = new HashMap<>();
  private final Bucketer fallback;

  public Experiments(Map<String, ApiProperties.Experiments> config) {
    config.forEach((domain, e) -> byDomain.put(domain, new Bucketer(e.salt(), e.variants())));
    var d = config.get("default");
    fallback = d == null ? new Bucketer("default", null) : new Bucketer(d.salt(), d.variants());
  }

  public Variant variant(String domain, String userId) {
    return bucketer(domain).assign(userId);
  }

  public Assignment assignment(String domain, String userId) {
    Bucketer b = bucketer(domain);
    Variant v = b.assign(userId);
    return new Assignment(domain, v.id(), b.bucket(userId), v.ranker());
  }

  private Bucketer bucketer(String domain) {
    return byDomain.getOrDefault(domain, fallback);
  }
}
