package com.recsys.api.core;

/** Why an item was recommended; clients render a template per code. */
public enum ReasonCode {
  SIMILAR_TO_RECENT,
  SIMILAR_TO_TASTE,
  LISTENED_TOGETHER,
  OFTEN_PLAYED_NEXT,
  TRENDING,
  POPULAR_IN_REGION,
  NEW_FOR_YOU,
  ONBOARDING_MATCH,
  POPULAR_FALLBACK
}
