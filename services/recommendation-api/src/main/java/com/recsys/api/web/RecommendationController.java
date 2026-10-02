package com.recsys.api.web;

import com.recsys.api.config.ApiProperties;
import com.recsys.api.core.RecRequest;
import com.recsys.api.core.RecommendationService;
import com.recsys.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecommendationController {
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
  private static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  private static final Set<String> SURFACES = Set.of("home", "next_track", "radio");

  private final RecommendationService service;
  private final ApiProperties props;

  public RecommendationController(RecommendationService service, ApiProperties props) {
    this.service = service;
    this.props = props;
  }

  public record Item(
      String itemId,
      int position,
      double score,
      String recommendationId,
      String reasonCode,
      Map<String, String> reasonContext,
      String explanation,
      boolean explore) {}

  public record Response(
      String recommendationId,
      String userId,
      String domain,
      String context,
      String variantId,
      String rankerVersion,
      String indexVersion,
      String fallbackLevel,
      Instant generatedAt,
      List<Item> items) {}

  @GetMapping("/v1/recommendations")
  public ResponseEntity<Response> recommend(
      @RequestParam String userId,
      @RequestParam String domain,
      @RequestParam(defaultValue = "home") String context,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String seedItemId,
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String device,
      @RequestParam(defaultValue = "true") boolean explicit) {
    if (!ID.matcher(userId).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "invalid userId");
    }
    if (!props.enabledDomains().contains(domain)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "DOMAIN_NOT_ENABLED", domain + " is not enabled yet");
    }
    if (!SURFACES.contains(context)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "context must be one of " + SURFACES);
    }
    int n = limit == null ? props.defaultLimit() : limit;
    if (n < 1 || n > props.maxLimit()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_LIMIT", "limit must be 1.." + props.maxLimit());
    }
    if (seedItemId != null && !ID.matcher(seedItemId).matches()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SEED_ITEM", "invalid seedItemId");
    }
    if (country != null && !COUNTRY.matcher(country).matches()) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "INVALID_COUNTRY", "ISO-3166 alpha-2 expected");
    }
    var result =
        service.recommend(
            new RecRequest(
                userId, domain, context, n, sessionId, seedItemId, country, device, explicit));
    List<Item> items = new java.util.ArrayList<>();
    for (int i = 0; i < result.items().size(); i++) {
      var c = result.items().get(i);
      items.add(
          new Item(
              c.itemId,
              i,
              Math.round(c.score * 10_000) / 10_000.0,
              result.recommendationId(),
              c.reason == null ? "POPULAR_FALLBACK" : c.reason.name(),
              c.seedItemId == null ? Map.of() : Map.of("seedItemId", c.seedItemId),
              null, // TODO(phase-2): cached LLM "why this" explanation (read-only lookup)
              c.explore));
    }
    var body =
        new Response(
            result.recommendationId(),
            userId,
            domain,
            context,
            result.variantId(),
            result.rankerVersion(),
            result.indexVersion(),
            result.fallbackLevel().name(),
            result.generatedAt(),
            items);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
