package com.recsys.openai;

/**
 * ESSENTIAL jobs (new-item embedding, onboarding) keep running past the monthly budget because they
 * are cheap and user-visible; NON_ESSENTIAL jobs (backfills, enrichment, explanations) pause.
 */
public enum JobPriority {
  ESSENTIAL,
  NON_ESSENTIAL
}
