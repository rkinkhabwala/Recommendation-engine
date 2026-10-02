package com.recsys.api.core;

import com.recsys.api.config.ApiProperties.Variant;
import com.recsys.common.Deadline;
import com.recsys.common.Vectors;
import com.recsys.features.model.RecentInteraction;
import com.recsys.features.model.UserFeatures;
import com.recsys.features.model.UserShortTerm;
import com.recsys.features.model.UserVector;
import java.util.List;
import java.util.Map;

/** Everything a request needs, computed once: the user's vectors, features and deadline. */
public record RecContext(
    RecRequest request,
    Variant variant,
    UserFeatures features,
    float[] shortVector,
    float[] longVector,
    float[] seedVector,
    float[] queryVector,
    ReasonCode semanticReason,
    long now,
    Deadline deadline,
    boolean logFeatures) {

  public static RecContext of(
      RecRequest request,
      Variant variant,
      UserFeatures f,
      String indexVersion,
      double shortBlend,
      long now,
      Deadline deadline,
      boolean logFeatures) {
    float[] st =
        f.shortTerm() == null
            ? null
            : vector(f.shortTerm().indexVersion(), f.shortTerm().vector(), indexVersion);
    float[] lt = vector(f.longTerm(), indexVersion);
    float[] seed = vector(f.seed(), indexVersion);
    float[] query;
    ReasonCode reason;
    if (st != null || lt != null) {
      query =
          Vectors.normalized(Vectors.blend(st, (float) shortBlend, lt, (float) (1 - shortBlend)));
      reason = st != null ? ReasonCode.SIMILAR_TO_RECENT : ReasonCode.SIMILAR_TO_TASTE;
    } else {
      query = seed;
      reason = ReasonCode.ONBOARDING_MATCH;
    }
    return new RecContext(
        request, variant, f, st, lt, seed, query, reason, now, deadline, logFeatures);
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
}
