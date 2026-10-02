package com.recsys.stream.topology;

import com.recsys.events.v1.CatalogItem;
import com.recsys.features.model.ItemMeta;
import java.util.List;
import java.util.Locale;

/** Catalog record → serving metadata ({@code i:{id}:meta}). */
final class CatalogMeta {
  private CatalogMeta() {}

  static ItemMeta of(CatalogItem i) {
    return new ItemMeta(
        i.getItemId(),
        i.getDomain().name().toLowerCase(Locale.ROOT),
        i.getTitle(),
        i.getCreatorId(),
        i.getCreatorName(),
        List.copyOf(i.getGenres()),
        List.copyOf(i.getMoodTags()),
        i.getDurationMs(),
        i.getReleaseDate(),
        i.getExplicit(),
        List.copyOf(i.getAvailableRegions()),
        i.getCreatedAt().toEpochMilli());
  }
}
