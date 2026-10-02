package com.recsys.enrichment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Strict JSON schemas for structured outputs (all properties required, no extras). */
final class EnrichmentSchema {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String ENRICHMENT = "item_enrichment";
  static final String EXPLANATION = "explanation";

  private EnrichmentSchema() {}

  static JsonNode enrichment() {
    ObjectNode props = JSON.createObjectNode();
    props.set("moods", array(enumOf(Vocabulary.MOODS), 3));
    props.set("themes", array(string(), 5));
    props.set("topics", array(string(), 5));
    props.set("tone", nullableEnum(Vocabulary.TONES));
    props.set("reading_level", nullableEnum(Vocabulary.READING_LEVELS));
    return object(props, List.of("moods", "themes", "topics", "tone", "reading_level"));
  }

  static JsonNode explanation() {
    ObjectNode props = JSON.createObjectNode();
    props.set("text", string());
    return object(props, List.of("text"));
  }

  private static ObjectNode object(ObjectNode props, List<String> required) {
    ObjectNode o = JSON.createObjectNode();
    o.put("type", "object");
    o.set("properties", props);
    ArrayNode req = o.putArray("required");
    required.forEach(req::add);
    o.put("additionalProperties", false);
    return o;
  }

  private static ObjectNode string() {
    return JSON.createObjectNode().put("type", "string");
  }

  private static ObjectNode enumOf(List<String> values) {
    ObjectNode n = string();
    ArrayNode e = n.putArray("enum");
    values.forEach(e::add);
    return n;
  }

  private static ObjectNode nullableEnum(List<String> values) {
    ObjectNode n = JSON.createObjectNode();
    ArrayNode t = n.putArray("type");
    t.add("string");
    t.add("null");
    ArrayNode e = n.putArray("enum");
    values.forEach(e::add);
    e.addNull();
    return n;
  }

  private static ObjectNode array(ObjectNode items, int maxItems) {
    ObjectNode n = JSON.createObjectNode();
    n.put("type", "array");
    n.set("items", items);
    n.put("maxItems", maxItems);
    return n;
  }
}
