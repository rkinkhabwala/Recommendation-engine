package com.recsys.features.model;

/** Real-time item statistics (Redis {@code i:{id}:stat}); rates are Bayesian-smoothed. */
public record ItemStats(
    String itemId,
    double impressions,
    double plays,
    double ctr,
    double completionRate,
    double skipRate,
    double plays1h,
    double velocity,
    long updatedTs) {}
