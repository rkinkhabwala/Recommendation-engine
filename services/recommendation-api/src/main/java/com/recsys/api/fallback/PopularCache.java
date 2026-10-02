package com.recsys.api.fallback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.features.FeatureReader;
import com.recsys.features.model.ScoredItem;
import com.recsys.features.model.TrendingList;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * In-process popular lists refreshed from Redis every minute, so recommendations survive a full
 * Redis outage. The bundled static list covers a cold process with Redis down. TODO(phase-3):
 * generate the static list from trending at deploy time.
 */
public class PopularCache {
  private static final Logger log = LoggerFactory.getLogger(PopularCache.class);

  private final FeatureReader features;
  private final Map<String, List<String>> lists = new ConcurrentHashMap<>();
  private final List<String> staticList;

  public PopularCache(FeatureReader features, String staticResource) {
    this.features = features;
    this.staticList = loadStatic(staticResource);
  }

  public record Popular(List<String> items, boolean fromStatic) {}

  /** Region list first, then global; never throws. */
  public Popular get(String domain, String region) {
    if (region != null) {
      lists.putIfAbsent(key(domain, region), List.of()); // register for refresh
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (region != null) {
      out.addAll(lists.getOrDefault(key(domain, region), List.of()));
    }
    out.addAll(lists.getOrDefault(key(domain, "GLOBAL"), List.of()));
    if (out.isEmpty()) {
      return new Popular(staticList, true);
    }
    return new Popular(new ArrayList<>(out), false);
  }

  @Scheduled(fixedDelayString = "${recs.api.popular-refresh:60s}", initialDelay = 0)
  public void refresh() {
    lists.putIfAbsent(key("song", "GLOBAL"), List.of());
    for (String key : List.copyOf(lists.keySet())) {
      String[] p = key.split("\\|");
      try {
        TrendingList t = features.trending(p[0], p[1], Duration.ofSeconds(2));
        if (t != null && !t.items().isEmpty()) {
          lists.put(key, t.items().stream().map(ScoredItem::itemId).limit(200).toList());
        }
      } catch (RuntimeException e) {
        log.debug(
            "Popular refresh failed for {}: {}", key, e.toString()); // keep the last good list
      }
    }
  }

  private static String key(String domain, String region) {
    return domain + "|" + region;
  }

  private static List<String> loadStatic(String resource) {
    try (InputStream in = PopularCache.class.getResourceAsStream(resource)) {
      if (in == null) {
        return List.of();
      }
      String[] ids = new ObjectMapper().readValue(in, String[].class);
      return List.of(ids);
    } catch (Exception e) {
      log.warn("Could not load static fallback list {}: {}", resource, e.toString());
      return List.of();
    }
  }
}
