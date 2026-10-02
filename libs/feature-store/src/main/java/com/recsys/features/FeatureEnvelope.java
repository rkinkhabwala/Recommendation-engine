package com.recsys.features;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Value format of the compacted {@code features.*} topics. The Kafka key is the Redis key; a null
 * value (tombstone) means delete.
 *
 * @param seq per-key monotonic version maintained in the stream processor's state; the writer
 *     applies {@code data} only if {@code seq} is greater than what Redis holds
 * @param ttlSeconds Redis TTL, 0 = none
 * @param sourceTs receive time of the newest event reflected in {@code data} (freshness metric)
 */
public record FeatureEnvelope(long seq, long ttlSeconds, long sourceTs, JsonNode data) {

  public static byte[] encode(long seq, long ttlSeconds, long sourceTs, Object data) {
    return FeatureJson.write(
        new FeatureEnvelope(seq, ttlSeconds, sourceTs, FeatureJson.MAPPER.valueToTree(data)));
  }

  public static FeatureEnvelope decode(byte[] bytes) {
    return FeatureJson.read(bytes, FeatureEnvelope.class);
  }

  public byte[] dataBytes() {
    try {
      return FeatureJson.MAPPER.writeValueAsBytes(data);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
