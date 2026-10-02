package com.recsys.stream.config;

import com.recsys.stream.signals.SignalWeights;
import com.recsys.stream.topology.TopologySettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param replicationFactor for internal topics (1 locally, 3 in prod)
 * @param standbyReplicas warm standby state copies for fast failover
 */
@ConfigurationProperties("recs.stream")
public record StreamProperties(
    String applicationId,
    String stateDir,
    String schemaRegistryUrl,
    int replicationFactor,
    int standbyReplicas,
    Duration commitInterval,
    Duration maxCompactionLag,
    TopologySettings topology,
    SignalWeights songSignals) {}
