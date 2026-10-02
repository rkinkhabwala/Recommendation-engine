package com.recsys.enrichment;

import com.github.benmanes.caffeine.cache.Cache;
import com.recsys.events.v1.RecommendationServed;
import com.recsys.events.v1.ServedItem;
import com.recsys.features.FeatureEnvelope;
import com.recsys.features.FeatureReader;
import com.recsys.features.RedisKeys;
import com.recsys.features.model.Explanation;
import com.recsys.features.model.ItemMeta;
import com.recsys.openai.ChatClient;
import com.recsys.openai.ChatClient.StructuredRequest;
import com.recsys.openai.JobPriority;
import com.recsys.openai.OpenAiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Generates short "why this" sentences for the top items of a sample of served lists. Keys and
 * prompts depend only on (domain, reason, seed item, item) — never on the user — so one sentence is
 * reused by every user who gets the same recommendation for the same reason, and no user data
 * reaches the LLM. Results go through {@code features.item.v1} (Redis stays rebuildable); serving
 * only reads the cache and falls back to a client-side template on a miss.
 */
public final class ExplanationGenerator {
  private static final Set<String> SKIP_REASONS = Set.of("POPULAR_FALLBACK");
  private static final Pattern FORBIDDEN = Pattern.compile("(?i)(https?://|www\\.|@)");
  static final String SYSTEM =
      """
      Write one short, friendly sentence (at most 20 words) telling a listener/reader/viewer why
      they might enjoy the recommended item, based only on the reason and the item details given.
      Do not invent facts, do not mention personal data, history or numbers, no URLs.""";

  public interface Sink {
    void publish(String redisKey, byte[] envelope);
  }

  private final ChatClient llm;
  private final FeatureReader features;
  private final Sink sink;
  private final EnrichmentProperties props;
  private final Cache<String, Boolean> recentlyGenerated;
  private final MeterRegistry metrics;
  private final Clock clock;

  public ExplanationGenerator(
      ChatClient llm,
      FeatureReader features,
      Sink sink,
      EnrichmentProperties props,
      Cache<String, Boolean> recentlyGenerated,
      MeterRegistry metrics,
      Clock clock) {
    this.llm = llm;
    this.features = features;
    this.sink = sink;
    this.props = props;
    this.recentlyGenerated = recentlyGenerated;
    this.metrics = metrics;
    this.clock = clock;
  }

  boolean sampled(String recommendationId) {
    return Math.floorMod(recommendationId.hashCode(), 100) < props.explanationSamplePercent();
  }

  public void process(RecommendationServed served) {
    if (!sampled(served.getRecommendationId())) {
      return;
    }
    String domain = served.getDomain().name().toLowerCase(Locale.ROOT);
    Map<String, ServedItem> wanted = new LinkedHashMap<>();
    for (ServedItem item : served.getItems().stream().limit(props.explanationTopN()).toList()) {
      if (SKIP_REASONS.contains(item.getReasonCode())) {
        continue;
      }
      String key =
          RedisKeys.explanation(
              domain, item.getReasonCode(), item.getSeedItemId(), item.getItemId());
      if (recentlyGenerated.getIfPresent(key) == null) {
        wanted.put(key, item);
      }
    }
    if (wanted.isEmpty()) {
      return;
    }
    Duration timeout = Duration.ofSeconds(2);
    Map<String, Explanation> existing = features.explanations(wanted.keySet(), timeout);
    existing.keySet().forEach(k -> recentlyGenerated.put(k, true));
    wanted.keySet().removeAll(existing.keySet());
    if (wanted.isEmpty()) {
      return;
    }
    Set<String> ids = new HashSet<>();
    wanted
        .values()
        .forEach(
            i -> {
              ids.add(i.getItemId());
              if (i.getSeedItemId() != null) {
                ids.add(i.getSeedItemId());
              }
            });
    Map<String, ItemMeta> meta = features.itemMeta(ids, timeout);
    for (var e : wanted.entrySet()) {
      ServedItem item = e.getValue();
      ItemMeta m = meta.get(item.getItemId());
      if (m == null) {
        continue;
      }
      String text =
          generate(
              domain,
              item.getReasonCode(),
              m,
              item.getSeedItemId() == null ? null : meta.get(item.getSeedItemId()));
      if (text == null) {
        continue;
      }
      long now = clock.millis();
      sink.publish(
          e.getKey(),
          FeatureEnvelope.encode(
              now, props.explanationTtl().toSeconds(), 0, new Explanation(text, llm.model(), now)));
      recentlyGenerated.put(e.getKey(), true);
      metrics
          .counter("recs_explanations_total", "result", "generated", "domain", domain)
          .increment();
    }
  }

  private String generate(String domain, String reason, ItemMeta item, ItemMeta seed) {
    String prompt = prompt(domain, reason, item, seed);
    try {
      var res =
          llm.complete(
              new StructuredRequest(
                  SYSTEM, prompt, EnrichmentSchema.EXPLANATION, EnrichmentSchema.explanation(), 80),
              "explanations",
              JobPriority.NON_ESSENTIAL);
      return clean(res.json().path("text").asText(null));
    } catch (OpenAiException ex) {
      if (ex.kind() == OpenAiException.Kind.BAD_INPUT) {
        metrics
            .counter("recs_explanations_total", "result", "rejected", "domain", domain)
            .increment();
        return null;
      }
      throw ex;
    }
  }

  static String prompt(String domain, String reason, ItemMeta item, ItemMeta seed) {
    List<String> lines = new ArrayList<>();
    lines.add("domain: " + domain);
    lines.add("reason: " + reason);
    lines.add("item: " + describe(item));
    if (seed != null) {
      lines.add("because of: " + describe(seed));
    }
    return String.join("\n", lines);
  }

  private static String describe(ItemMeta m) {
    return m.title()
        + " | by "
        + m.artistName()
        + " | genres: "
        + String.join(", ", m.genres())
        + " | moods: "
        + String.join(", ", m.moods());
  }

  /** Single line, ≤ 160 chars, no links/handles. */
  static String clean(String text) {
    if (text == null) {
      return null;
    }
    String t = text.replaceAll("\\s+", " ").strip();
    if (t.isEmpty() || t.length() > 160 || FORBIDDEN.matcher(t).find()) {
      return null;
    }
    return t;
  }
}
