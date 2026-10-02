package com.recsys.api.experiment;

import com.recsys.api.config.ApiProperties.Variant;
import com.recsys.api.ranking.RankerWeights;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.common.utils.Utils;

/**
 * Deterministic A/B bucketing: {@code murmur2(salt + userId) mod 1000} → variant by range. Same
 * user, same salt → same variant on every request and every replica.
 */
public final class Bucketer {
  public static final Variant CONTROL = new Variant("control", 0, 1000, RankerWeights.defaults());

  private final String salt;
  private final List<Variant> variants;

  public Bucketer(String salt, List<Variant> variants) {
    this.salt = salt;
    this.variants = variants == null || variants.isEmpty() ? List.of(CONTROL) : variants;
  }

  public int bucket(String userId) {
    return Utils.toPositive(Utils.murmur2((salt + ":" + userId).getBytes(StandardCharsets.UTF_8)))
        % 1000;
  }

  public Variant assign(String userId) {
    int b = bucket(userId);
    return variants.stream()
        .filter(v -> b >= v.from() && b < v.to())
        .findFirst()
        .orElse(variants.get(0));
  }
}
