package com.recsys.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes enrichment through catalog-service (the catalog's single writer). */
final class HttpCatalogApi implements ItemEnricher.CatalogApi {
  private static final Logger log = LoggerFactory.getLogger(HttpCatalogApi.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String baseUrl;
  private final String apiKey;

  HttpCatalogApi(String baseUrl, String apiKey) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  @Override
  public boolean applyEnrichment(String itemId, EnrichmentValidator.Valid e) {
    Map<String, Object> body = new HashMap<>();
    body.put("version", e.version());
    body.put("moods", e.moods());
    body.put("themes", e.themes());
    body.put("topics", e.topics());
    body.put("tone", e.tone());
    body.put("readingLevel", e.readingLevel());
    try {
      HttpRequest req =
          HttpRequest.newBuilder(
                  URI.create(baseUrl + "/v1/catalog/items/" + itemId + "/enrichment"))
              .timeout(Duration.ofSeconds(10))
              .header("Content-Type", "application/json")
              .header("X-Api-Key", apiKey)
              .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
              .build();
      HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() >= 500) {
        throw new IllegalStateException(
            "catalog-service " + res.statusCode()); // retry with backoff
      }
      if (res.statusCode() >= 400) {
        log.warn(
            "Enrichment for {} rejected by catalog: {} {}",
            itemId,
            res.statusCode(),
            new String(res.body()));
        return false;
      }
      return JSON.readTree(res.body()).path("applied").asBoolean(false);
    } catch (IOException ex) {
      throw new IllegalStateException("catalog-service unreachable", ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }
}
