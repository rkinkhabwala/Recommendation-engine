package com.recsys.embedding;

import com.recsys.events.v1.CatalogItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Builds the text that is embedded for an item, per domain. Songs: title, artist, genres, mood tags
 * (no lyrics). The content hash covers template version, model and dimensions, so changing any of
 * them re-embeds.
 */
public final class ItemText {
  private ItemText() {}

  public static String of(CatalogItem item) {
    return switch (item.getDomain()) {
      case SONG ->
          "%s by %s. Genres: %s. Mood: %s."
              .formatted(
                  item.getTitle(),
                  item.getCreatorName(),
                  String.join(", ", item.getGenres()),
                  String.join(", ", item.getMoodTags()));
      // TODO(phase-2): book = title + description + genres; video = title + description +
      // transcript summary; post = post text.
      default ->
          "%s by %s. %s %s"
              .formatted(
                  item.getTitle(),
                  item.getCreatorName(),
                  String.join(", ", item.getGenres()),
                  item.getDescription() == null ? "" : item.getDescription());
    };
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
