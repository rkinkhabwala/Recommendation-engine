package com.recsys.stream.model;

import com.recsys.common.Vectors;
import com.recsys.events.v1.CatalogItem;
import com.recsys.events.v1.ItemEmbedding;
import java.util.List;

/**
 * Catalog metadata joined with the item's embedding (KTable-KTable left join). {@code vector} is
 * float16 and may be null while the item is not embedded yet.
 */
public record ItemProfile(
    String itemId,
    String artistId,
    List<String> genres,
    List<String> moods,
    Long durationMs,
    String indexVersion,
    byte[] vector) {

  public static ItemProfile of(CatalogItem item, ItemEmbedding embedding) {
    return new ItemProfile(
        item.getItemId(),
        item.getCreatorId(),
        List.copyOf(item.getGenres()),
        List.copyOf(item.getMoodTags()),
        item.getDurationMs(),
        embedding == null ? null : embedding.getIndexVersion(),
        embedding == null ? null : Vectors.toFloat16(Vectors.fromList(embedding.getVector())));
  }
}
