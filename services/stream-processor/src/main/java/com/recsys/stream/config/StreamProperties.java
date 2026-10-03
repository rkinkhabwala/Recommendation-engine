package com.recsys.stream.config;

import com.recsys.stream.signals.SignalWeights;
import com.recsys.stream.topology.TopologySettings;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param replicationFactor for internal topics (1 locally, 3 in prod)
 * @param standbyReplicas warm standby state copies for fast failover
 * @param numThreads stream threads per instance (tasks = partitions are spread over all threads)
 * @param embeddingsTopic embeddings topic of the current index (catalog.embeddings.v1 by default)
 * @param signals per-domain signal weights (song, book, video, post)
 */
@ConfigurationProperties("recs.stream")
public record StreamProperties(
    String applicationId,
    String stateDir,
    String schemaRegistryUrl,
    int replicationFactor,
    int standbyReplicas,
    int numThreads,
    Duration commitInterval,
    Duration maxCompactionLag,
    String embeddingsTopic,
    TopologySettings topology,
    Map<String, SignalWeights> signals) {}
