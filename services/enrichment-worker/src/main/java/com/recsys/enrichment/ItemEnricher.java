package com.recsys.enrichment;

import com.recsys.events.v1.CatalogItem;
import com.recsys.openai.ChatClient;
import com.recsys.openai.ChatClient.StructuredRequest;
import com.recsys.openai.JobPriority;
import com.recsys.openai.OpenAiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills missing structured metadata (moods, themes, topics, tone, reading level) for catalog items
 * using structured outputs. Only item content is sent — never user data. The validated result is
 * written through the catalog API, which bumps the item's seq: the item is re-published and
 * re-embedded (enriched fields are part of the embedding text), so retrieval improves too.
 */
public final class ItemEnricher {
  private static final Logger log = LoggerFactory.getLogger(ItemEnricher.class);
  static final String SYSTEM =
      """
      You label catalog items for a recommender system. Use only the item content provided.
      moods: up to 3 from the allowed list that fit the item. themes: up to 5 short lowercase
      phrases about what the item is about. topics: up to 5 short lowercase subject areas.
      tone: the item's tone or null. reading_level: only for books and posts, otherwise null.
      Never include people's names, URLs or contact details.""";

  public interface CatalogApi {
    /**
     * @return true if applied
     */
    boolean applyEnrichment(String itemId, EnrichmentValidator.Valid enrichment);
  }

  private final ChatClient llm;
  private final CatalogApi catalog;
  private final EnrichmentProperties props;
  private final MeterRegistry metrics;
  private final long intervalNanos;
  private long nextSlotNanos = System.nanoTime();

  public ItemEnricher(
      ChatClient llm, CatalogApi catalog, EnrichmentProperties props, MeterRegistry metrics) {
    this.llm = llm;
    this.catalog = catalog;
    this.props = props;
    this.metrics = metrics;
    this.intervalNanos =
        props.maxItemsPerSecond() > 0 ? (long) (1e9 / props.maxItemsPerSecond()) : 0;
  }

  private synchronized long reserveSlot() {
    long now = System.nanoTime();
    nextSlotNanos = Math.max(nextSlotNanos, now) + intervalNanos;
    return nextSlotNanos - intervalNanos;
  }

  private void throttle() {
    if (intervalNanos == 0) {
      return;
    }
    long slot = reserveSlot();
    long wait;
    while ((wait = slot - System.nanoTime()) > 0) {
      java.util.concurrent.locks.LockSupport.parkNanos(wait);
    }
  }

  boolean needsEnrichment(CatalogItem item) {
    String domain = item.getDomain().name().toLowerCase(Locale.ROOT);
    boolean stale =
        item.getEnrichmentVersion() == null || item.getEnrichmentVersion() < props.version();
    boolean missing =
        item.getMoodTags().isEmpty() || item.getThemes().isEmpty() || item.getTopics().isEmpty();
    return props.domains().contains(domain) && stale && missing;
  }

  /** Processes one item; retryable failures propagate (the consumer backs off and retries). */
  public void process(CatalogItem item) {
    if (item == null || !needsEnrichment(item)) {
      return;
    }
    throttle();
    String domain = item.getDomain().name().toLowerCase(Locale.ROOT);
    var request =
        new StructuredRequest(
            SYSTEM,
            prompt(item, domain),
            EnrichmentSchema.ENRICHMENT,
            EnrichmentSchema.enrichment(),
            400);
    EnrichmentValidator.Valid valid;
    try {
      valid =
          EnrichmentValidator.validate(
              llm.complete(request, "enrichment", JobPriority.NON_ESSENTIAL).json(),
              props.version(),
              domain);
    } catch (OpenAiException e) {
      if (e.kind() == OpenAiException.Kind.BAD_INPUT) {
        count("rejected");
        log.warn("Enrichment rejected for {}: {}", item.getItemId(), e.getMessage());
        return; // will not succeed on retry; item keeps curated metadata only
      }
      throw e;
    }
    if (valid == null) {
      count("invalid");
      return;
    }
    count(catalog.applyEnrichment(item.getItemId(), valid) ? "applied" : "stale");
  }

  static String prompt(CatalogItem i, String domain) {
    StringBuilder b = new StringBuilder();
    b.append("domain: ").append(domain).append('\n');
    b.append("title: ").append(i.getTitle()).append('\n');
    b.append("creator: ").append(i.getCreatorName()).append('\n');
    b.append("genres: ").append(String.join(", ", i.getGenres())).append('\n');
    if (!i.getMoodTags().isEmpty()) {
      b.append("curated moods: ").append(String.join(", ", i.getMoodTags())).append('\n');
    }
    if (i.getDescription() != null) {
      String d = i.getDescription();
      b.append("description: ").append(d.length() > 1500 ? d.substring(0, 1500) : d).append('\n');
    }
    if (i.getTranscriptSummary() != null) {
      b.append("transcript summary: ").append(i.getTranscriptSummary()).append('\n');
    }
    return b.toString();
  }

  private void count(String result) {
    metrics.counter("recs_enrichment_items_total", "result", result).increment();
  }
}
