package com.recsys.embedding;

import com.recsys.events.v1.CatalogItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds the text embedded for an item, per domain (template version "v2"):
 *
 * <ul>
 *   <li>song: title, artist, genres, mood tags (+ enriched themes). No lyrics.
 *   <li>book: title, author, genres, themes, description
 *   <li>video: title, channel, genres/topics, description, transcript summary
 *   <li>post: post text, topics, tone
 * </ul>
 *
 * All domains are embedded into one space so taste transfers across domains. The content hash
 * covers template version, model and dimensions, so changing any of them re-embeds.
 */
public final class ItemText {
  private static final int MAX_DESCRIPTION = 1_500;

  private ItemText() {}

  public static String of(CatalogItem item) {
    List<String> parts = new ArrayList<>();
    switch (item.getDomain()) {
      case SONG -> {
        parts.add(item.getTitle() + " by " + item.getCreatorName() + ".");
        parts.add("Genres: " + join(item.getGenres()) + ".");
        parts.add("Mood: " + join(item.getMoodTags()) + ".");
        add(parts, "Themes: ", item.getThemes());
      }
      case BOOK -> {
        parts.add(item.getTitle() + " by " + item.getCreatorName() + ".");
        parts.add("Genres: " + join(item.getGenres()) + ".");
        add(parts, "Themes: ", item.getThemes());
        add(parts, "Mood: ", item.getMoodTags());
        addText(parts, item.getDescription());
      }
      case VIDEO -> {
        parts.add(item.getTitle() + " by " + item.getCreatorName() + ".");
        add(parts, "Topics: ", union(item.getGenres(), item.getTopics()));
        addText(parts, item.getDescription());
        addText(parts, item.getTranscriptSummary());
      }
      case POST -> {
        addText(parts, item.getDescription() == null ? item.getTitle() : item.getDescription());
        add(parts, "Topics: ", union(item.getGenres(), item.getTopics()));
        if (item.getTone() != null) {
          parts.add("Tone: " + item.getTone() + ".");
        }
      }
      default -> {
        parts.add(item.getTitle() + " by " + item.getCreatorName() + ".");
        addText(parts, item.getDescription());
      }
    }
    return String.join(" ", parts);
  }

  private static void add(List<String> parts, String label, List<String> tags) {
    if (tags != null && !tags.isEmpty()) {
      parts.add(label + join(tags) + ".");
    }
  }

  private static void addText(List<String> parts, String text) {
    if (text != null && !text.isBlank()) {
      parts.add(
          text.length() > MAX_DESCRIPTION ? text.substring(0, MAX_DESCRIPTION) : text.strip());
    }
  }

  private static List<String> union(List<String> a, List<String> b) {
    LinkedHashSet<String> s = new LinkedHashSet<>(a);
    s.addAll(b);
    return List.copyOf(s);
  }

  private static String join(List<String> tags) {
    return String.join(", ", tags);
  }

  public static String hash(String templateVersion, String model, int dims, String text) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      byte[] digest =
          sha.digest(
              (templateVersion + "|" + model + "|" + dims + "|" + text)
                  .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
