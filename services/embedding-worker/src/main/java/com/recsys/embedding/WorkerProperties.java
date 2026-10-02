package com.recsys.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param indexVersion Qdrant collection to write, e.g. items_te3s_512_v1 (dims encoded)
 * @param alias alias the serving path reads
 * @param templateVersion version of the text templates; part of the content hash
 * @param embedBatchSize inputs per embeddings request
 * @param embeddingsTopic topic the current index's embedding records go to
 */
@ConfigurationProperties("recs.embedding")
public record WorkerProperties(
    String indexVersion,
    String alias,
    String templateVersion,
    int embedBatchSize,
    String qdrantHost,
    int qdrantPort,
    Duration qdrantTimeout,
    Duration userKeyTtl,
    String embeddingsTopic) {

  public WorkerProperties {
    if (embeddingsTopic == null || embeddingsTopic.isBlank()) {
      embeddingsTopic = com.recsys.common.Topics.CATALOG_EMBEDDINGS;
    }
  }
}
