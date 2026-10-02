package com.recsys.catalog;

import com.recsys.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {
  private static final Logger log = LoggerFactory.getLogger(CatalogController.class);
  private static final int MAX_BATCH = 1000;

  private final CatalogRepository repository;
  private final CatalogPublisher publisher;

  public CatalogController(CatalogRepository repository, CatalogPublisher publisher) {
    this.repository = repository;
    this.publisher = publisher;
  }

  public record UpsertRequest(List<CatalogItemDto> items) {}

  public record Rejection(int index, String itemId, String error) {}

  public record UpsertResponse(int changed, int unchanged, List<Rejection> rejected) {}

  @PostMapping("/v1/catalog/items")
  public ResponseEntity<UpsertResponse> upsert(@RequestBody UpsertRequest request) {
    if (request.items() == null
        || request.items().isEmpty()
        || request.items().size() > MAX_BATCH) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BATCH", "1.." + MAX_BATCH + " items");
    }
    int changed = 0;
    int unchanged = 0;
    List<Rejection> rejected = new ArrayList<>();
    boolean publishHealthy = true;
    for (int i = 0; i < request.items().size(); i++) {
      CatalogItemDto item = request.items().get(i);
      String error = item.validate();
      if (error != null) {
        rejected.add(new Rejection(i, item.itemId(), error));
        continue;
      }
      var stored = repository.upsert(item);
      if (stored.isEmpty()) {
        unchanged++;
        continue;
      }
      changed++;
      if (publishHealthy) {
        try {
          publisher.publish(stored.get());
        } catch (Exception e) {
          // Committed in Postgres; the reconciler publishes it once Kafka/Redis recover.
          publishHealthy = false;
          log.warn("Publish failed, deferring to reconciler: {}", e.toString());
        }
      }
    }
    return ResponseEntity.accepted().body(new UpsertResponse(changed, unchanged, rejected));
  }

  /** Called by the enrichment worker with validated LLM output. */
  @org.springframework.web.bind.annotation.PatchMapping("/v1/catalog/items/{itemId}/enrichment")
  public ResponseEntity<Map<String, Object>> enrich(
      @PathVariable String itemId, @RequestBody Enrichment enrichment) {
    String error = enrichment.validate();
    if (error != null) {
      throw new ApiException(HttpStatus.BAD_REQUEST, error, "invalid enrichment");
    }
    var stored = repository.applyEnrichment(itemId, enrichment);
    if (stored.isEmpty()) {
      return ResponseEntity.ok(Map.of("itemId", itemId, "applied", false)); // same/older version
    }
    try {
      publisher.publish(stored.get());
    } catch (Exception e) {
      log.warn("Enrichment publish failed, deferring to reconciler: {}", e.toString());
    }
    return ResponseEntity.accepted()
        .body(Map.of("itemId", itemId, "applied", true, "seq", stored.get().seq()));
  }

  @GetMapping("/v1/catalog/items/{itemId}")
  public CatalogItemDto get(@PathVariable String itemId) {
    return repository
        .find(itemId)
        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", itemId));
  }

  @DeleteMapping("/v1/catalog/items/{itemId}")
  public ResponseEntity<Map<String, Object>> delete(@PathVariable String itemId) {
    long seq =
        repository
            .markDeleted(itemId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", itemId));
    try {
      publisher.publishDeletion(itemId, seq);
    } catch (Exception e) {
      log.warn("Deletion publish failed, deferring to reconciler: {}", e.toString());
    }
    return ResponseEntity.accepted().body(Map.of("itemId", itemId, "seq", seq));
  }
}
