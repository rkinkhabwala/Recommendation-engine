package com.recsys.api.core;

import com.recsys.api.config.ApiProperties.Candidates;
import com.recsys.api.config.ApiProperties.Rerank;
import com.recsys.api.config.ApiProperties.Variant;
import com.recsys.common.Deadline;
import com.recsys.common.Vectors;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.UserFeatures;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import java.util.List;
import java.util.Map;

/**
 * Everything a request needs, computed once: the user's vectors for the domain (plus cross-domain
 * taste), features, the domain's rules, the A/B variant and the deadline.
 */
public record RecContext(
    RecRequest request,
    Variant variant,
    Rerank rules,
    UserFeatures features,
    float[] shortVector,
    float[] longVector,
    float[] seedVector,
    float[] crossVector,
    float[] queryVector,
    ReasonCode semanticReason,
    long now,
    Deadline deadline,
    boolean logFeatures) {

  public static RecContext of(
      RecRequest request,
      Variant variant,
      Rerank rules,
      UserFeatures f,
      String indexVersion,
      Candidates blend,
      long now,
      Deadline deadline,
      boolean logFeatures) {
    float[] st =
        f.shortTerm() == null
            ? null
            : vector(f.shortTerm().indexVersion(), f.shortTerm().vector(), indexVersion);
    float[] lt = vector(f.longTerm(), indexVersion);
    float[] seed = vector(f.seed(), indexVersion);
    float[] cross = vector(f.crossDomain(), indexVersion);
    float[] query;
    ReasonCode reason;
    if (st != null || lt != null) {
      // In-domain taste first; a small cross-domain share keeps the space consistent.
      float[] domain =
          Vectors.blend(st, (float) blend.shortWeight(), lt, (float) blend.longWeight());
      query = Vectors.normalized(Vectors.blend(domain, 1f, cross, (float) blend.crossWeight()));
      reason = st != null ? ReasonCode.SIMILAR_TO_RECENT : ReasonCode.SIMILAR_TO_TASTE;
    } else if (cross != null) {
      query = seed == null ? cross : Vectors.normalized(Vectors.blend(cross, 1f, seed, 1f));
      reason = ReasonCode.CROSS_DOMAIN;
    } else {
      query = seed;
      reason = ReasonCode.ONBOARDING_MATCH;
    }
    return new RecContext(
        request, variant, rules, f, st, lt, seed, cross, query, reason, now, deadline, logFeatures);
  }

  /** Vectors from another embedding space are ignored (index migration safety). */
  private static float[] vector(UserVector v, String indexVersion) {
    return v == null ? null : vector(v.indexVersion(), v.vector(), indexVersion);
  }

  private static float[] vector(String version, byte[] f16, String indexVersion) {
    if (f16 == null || !indexVersion.equals(version)) {
      return null;
    }
    return Vectors.normalized(Vectors.fromFloat16(f16));
  }

  public String domain() {
    return request.domain();
  }

  public UserShortTerm shortTerm() {
    return features.shortTerm();
  }

  public List<RecentInteraction> recent() {
    return shortTerm() == null ? List.of() : shortTerm().recent();
  }

  public Map<String, Double> artistAffinity() {
    return shortTerm() == null ? Map.of() : shortTerm().artistAffinity();
  }

  public Map<String, Double> genreAffinity() {
    return shortTerm() == null ? Map.of() : shortTerm().genreAffinity();
  }

  public Map<String, Double> moodAffinity() {
    return shortTerm() == null ? Map.of() : shortTerm().moodAffinity();
  }

  public String region() {
    return request.country();
  }

  public String explorationStrategy() {
    return variant.exploration() != null ? variant.exploration() : rules.explorationStrategy();
  }
}
