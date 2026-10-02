package com.recsys.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Thin JSON-over-HTTP client for the public APIs (exactly what a real client would call). */
public final class ApiClient {
  static final ObjectMapper JSON =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final String ingestUrl;
  private final String recsUrl;
  private final String catalogUrl;
  private final String apiKey;

  public ApiClient(String ingestUrl, String recsUrl, String catalogUrl, String apiKey) {
    this.ingestUrl = ingestUrl;
    this.recsUrl = recsUrl;
    this.catalogUrl = catalogUrl;
    this.apiKey = apiKey;
  }

  public JsonNode postCatalog(List<Map<String, Object>> items)
      throws IOException, InterruptedException {
    return post(catalogUrl + "/v1/catalog/items", Map.of("items", items));
  }

  public JsonNode postEvents(List<Map<String, Object>> events)
      throws IOException, InterruptedException {
    return post(ingestUrl + "/v1/events", Map.of("events", events));
  }

  public JsonNode onboarding(String userId, Map<String, Object> body)
      throws IOException, InterruptedException {
    return post(ingestUrl + "/v1/users/" + userId + "/onboarding", body);
  }

  public JsonNode recommendations(Map<String, String> params)
      throws IOException, InterruptedException {
    StringBuilder q = new StringBuilder();
    params.forEach(
        (k, v) -> {
          if (v != null) {
            q.append(q.isEmpty() ? "?" : "&")
                .append(k)
                .append('=')
                .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
          }
        });
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(recsUrl + "/v1/recommendations" + q))
            .timeout(Duration.ofSeconds(2))
            .header("X-Api-Key", apiKey)
            .GET()
            .build();
    return send(req);
  }

  private JsonNode post(String url, Object body) throws IOException, InterruptedException {
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("X-Api-Key", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
            .build();
    return send(req);
  }

  private JsonNode send(HttpRequest req) throws IOException, InterruptedException {
    HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    if (res.statusCode() >= 300 && res.statusCode() != 429) {
      throw new IOException(
          "HTTP "
              + res.statusCode()
              + " "
              + req.uri()
              + ": "
              + new String(res.body(), StandardCharsets.UTF_8));
    }
    return JSON.readTree(res.body());
  }
}
