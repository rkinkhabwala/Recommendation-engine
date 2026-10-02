package com.recsys.enrichment;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param version enrichment prompt/schema version; bump to re-enrich the catalog
 * @param domains domains to enrich
 * @param explanationSamplePercent share of served lists whose top items get explanations
 * @param explanationTopN items per sampled list
 * @param explanationTtl Redis TTL of a cached explanation
 * @param maxItemsPerSecond enrichment throttle: every applied enrichment re-embeds the item and
 *     re-indexes it in Qdrant, so an unthrottled rollout becomes an indexing storm that competes
 *     with serving (bulk rollouts should go through the backfill path instead)
 */
@ConfigurationProperties("recs.enrichment")
public record EnrichmentProperties(
    int version,
    List<String> domains,
    String catalogUrl,
    String apiKey,
    int explanationSamplePercent,
    int explanationTopN,
    Duration explanationTtl,
    double maxItemsPerSecond) {}
