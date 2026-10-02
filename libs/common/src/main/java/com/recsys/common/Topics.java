package com.recsys.common;

/**
 * Kafka topic names. Partition counts, cleanup policies and retention are defined in
 * config/kafka/topics.conf (see docs/architecture.md section 6).
 */
public final class Topics {
  public static final String EVENTS_RAW = "events.raw.v1";
  public static final String EVENTS_LATE = "events.late.v1";
  public static final String USERS_ONBOARDING = "users.onboarding.v1";
  public static final String USERS_DELETION = "users.deletion.v1";
  public static final String CATALOG_ITEMS = "catalog.items.v1";
  public static final String CATALOG_EMBEDDINGS = "catalog.embeddings.v1";
  public static final String FEATURES_USER = "features.user.v1";
  public static final String FEATURES_ITEM = "features.item.v1";
  public static final String FEATURES_TRENDING = "features.trending.v1";
  public static final String RECS_SERVED = "recs.served.v1";
  public static final String RECS_ATTRIBUTED = "recs.attributed.v1";
  public static final String EMBEDDING_DLQ = "embedding.dlq.v1";

  private Topics() {}
}
