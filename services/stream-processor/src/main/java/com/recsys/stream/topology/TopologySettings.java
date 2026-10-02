package com.recsys.stream.topology;

import java.time.Duration;

/**
 * Tunables of the feature topology. Emission intervals of zero mean "emit on every update" (used by
 * tests).
 */
public record TopologySettings(
    String indexVersion,
    Duration dedupeWindow,
    Duration maxLateness,
    Duration shortTermHalfLife,
    Duration longTermHalfLife,
    Duration affinityHalfLife,
    Duration itemStatsHalfLife,
    Duration neighborHalfLife,
    Duration recentlyPlayedWindow,
    Duration longTermEmitInterval,
    Duration itemEmitInterval,
    Duration trendingWindow,
    Duration trendingGrace,
    Duration trendingEmitInterval,
    Duration attributionWindow,
    Duration userKeyTtl,
    Duration itemKeyTtl,
    double negativeCapRatio,
    double coEngagementMinWeight,
    int maxCoEmitsPerSession,
    int neighborTopK,
    double neighborMinSupport,
    int trendingTopN,
    int affinityMaxEntries) {

  public static TopologySettings defaults(String indexVersion) {
    return new TopologySettings(
        indexVersion,
        Duration.ofHours(24),
        Duration.ofHours(24),
        Duration.ofHours(2),
        Duration.ofDays(30),
        Duration.ofHours(6),
        Duration.ofHours(24),
        Duration.ofDays(7),
        Duration.ofHours(2),
        Duration.ofHours(1),
        Duration.ofSeconds(30),
        Duration.ofMinutes(15),
        Duration.ofMinutes(10),
        Duration.ofSeconds(30),
        Duration.ofMinutes(30),
        Duration.ofDays(30),
        Duration.ofDays(30),
        0.3,
        0.4,
        200,
        50,
        2.0,
        500,
        50);
  }
}
