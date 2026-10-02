package com.recsys.stream.signals;

import java.util.HashMap;
import java.util.Map;

/** One {@link SignalWeigher} per domain; unknown domains use the song weights. */
public final class SignalWeighers {
  private final Map<String, SignalWeigher> byDomain = new HashMap<>();
  private final SignalWeigher fallback;

  public SignalWeighers(Map<String, SignalWeights> weights) {
    weights.forEach((domain, w) -> byDomain.put(domain, new SignalWeigher(w)));
    fallback = byDomain.getOrDefault("song", new SignalWeigher(SignalWeights.songDefaults()));
  }

  public static SignalWeighers defaults() {
    Map<String, SignalWeights> m = new HashMap<>();
    for (String d : com.recsys.common.Domains.ALL) {
      m.put(d, SignalWeights.defaults(d));
    }
    return new SignalWeighers(m);
  }

  public SignalWeigher get(String domain) {
    return byDomain.getOrDefault(domain, fallback);
  }
}
