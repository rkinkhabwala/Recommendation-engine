package com.recsys.api.core;

/** Why an item was recommended; clients render a template per code (or the cached explanation). */
public enum ReasonCode {
  SIMILAR_TO_RECENT,
  SIMILAR_TO_TASTE,
  /** The user is new to this domain; their taste in other domains drove retrieval. */
  CROSS_DOMAIN,
  LISTENED_TOGETHER,
  OFTEN_PLAYED_NEXT,
  TRENDING,
  POPULAR_IN_REGION,
  NEW_FOR_YOU,
  ONBOARDING_MATCH,
  POPULAR_FALLBACK
}
