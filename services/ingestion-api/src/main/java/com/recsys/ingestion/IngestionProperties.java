package com.recsys.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxBatchSize events per POST
 * @param publishTimeout how long to wait for broker acks before reporting PUBLISH_FAILED
 * @param maxClockSkew client timestamps further in the future are clamped to receive time
 * @param userEventsPerSecond sustained per-user rate (bot guard)
 * @param userBurst per-user burst capacity
 */
@ConfigurationProperties("recs.ingestion")
public record IngestionProperties(
    int maxBatchSize,
    Duration publishTimeout,
    Duration maxClockSkew,
    double userEventsPerSecond,
    int userBurst,
    int maxFreeTextChars) {}
