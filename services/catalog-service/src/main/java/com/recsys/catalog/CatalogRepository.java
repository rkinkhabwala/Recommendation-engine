package com.recsys.catalog;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogRepository {
  private final JdbcClient jdbc;

  public CatalogRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts or updates; {@code seq} increments only when content actually changed, so idempotent
   * client retries do not create new versions. Returns the stored row when it changed.
   */
  @Transactional
  public Optional<CatalogItemDto> upsert(CatalogItemDto i) {
    return jdbc.sql(
            """
            WITH changed AS (
            INSERT INTO items (item_id, domain, title, creator_id, creator_name, genres, mood_tags,
                               duration_ms, release_date, explicit, available_regions, description,
                               transcript_summary)
            VALUES (:id, :domain, :title, :cid, :cname, :genres, :moods, :dur, :rel, :explicit, :regions, :descr,
                    :transcript)
            ON CONFLICT (item_id) DO UPDATE SET
                domain = EXCLUDED.domain, title = EXCLUDED.title, creator_id = EXCLUDED.creator_id,
                creator_name = EXCLUDED.creator_name, genres = EXCLUDED.genres,
                mood_tags = EXCLUDED.mood_tags, duration_ms = EXCLUDED.duration_ms,
                release_date = EXCLUDED.release_date, explicit = EXCLUDED.explicit,
                available_regions = EXCLUDED.available_regions, description = EXCLUDED.description,
                transcript_summary = EXCLUDED.transcript_summary,
                deleted = FALSE, seq = items.seq + 1, updated_at = now()
            WHERE items.deleted
               OR (items.domain, items.title, items.creator_id, items.creator_name, items.genres,
                   items.mood_tags, items.duration_ms, items.release_date, items.explicit,
                   items.available_regions, items.description, items.transcript_summary)
                  IS DISTINCT FROM
                  (EXCLUDED.domain, EXCLUDED.title, EXCLUDED.creator_id, EXCLUDED.creator_name,
                   EXCLUDED.genres, EXCLUDED.mood_tags, EXCLUDED.duration_ms, EXCLUDED.release_date,
                   EXCLUDED.explicit, EXCLUDED.available_regions, EXCLUDED.description,
                   EXCLUDED.transcript_summary)
            RETURNING *
            ), queued AS (INSERT INTO outbox (item_id) SELECT item_id FROM changed)
            SELECT * FROM changed
            """)
        .param("id", i.itemId())
        .param("domain", i.domain())
        .param("title", i.title())
        .param("cid", i.artistId())
        .param("cname", i.artistName())
        .param("genres", i.genres().toArray(String[]::new))
        .param("moods", i.moods().toArray(String[]::new))
        .param("dur", i.durationMs())
        .param("rel", i.releaseDate())
        .param("explicit", i.explicit())
        .param("regions", i.availableRegions().toArray(String[]::new))
        .param("descr", i.description())
        .param("transcript", i.transcriptSummary())
        .query(CatalogRepository::map)
        .optional();
  }

  /**
   * Applies a newer enrichment version (no-op for the same or an older version, so retries and
   * replays are idempotent). Bumps seq so the item is re-published and re-embedded.
   */
  public Optional<CatalogItemDto> applyEnrichment(String itemId, Enrichment e) {
    return jdbc.sql(
            """
            WITH changed AS (
              UPDATE items SET enriched_moods = :moods, themes = :themes, topics = :topics, tone = :tone,
                     reading_level = :level, enrichment_version = :version, seq = seq + 1, updated_at = now()
              WHERE item_id = :id AND NOT deleted
                AND (enrichment_version IS NULL OR enrichment_version < :version)
              RETURNING *
            ), queued AS (INSERT INTO outbox (item_id) SELECT item_id FROM changed)
            SELECT * FROM changed
            """)
        .param("id", itemId)
        .param("moods", e.moods().toArray(String[]::new))
        .param("themes", e.themes().toArray(String[]::new))
        .param("topics", e.topics().toArray(String[]::new))
        .param("tone", e.tone())
        .param("level", e.readingLevel())
        .param("version", e.version())
        .query(CatalogRepository::map)
        .optional();
  }

  public Optional<CatalogItemDto> find(String itemId) {
    return jdbc.sql("SELECT * FROM items WHERE item_id = :id AND NOT deleted")
        .param("id", itemId)
        .query(CatalogRepository::map)
        .optional();
  }

  /** Soft delete (keeps the row so the tombstone can be re-published). Returns the new seq. */
  public Optional<Long> markDeleted(String itemId) {
    return jdbc.sql(
            "WITH changed AS (UPDATE items SET deleted = TRUE, seq = seq + 1, updated_at = now() "
                + "WHERE item_id = :id AND NOT deleted RETURNING item_id, seq), "
                + "queued AS (INSERT INTO outbox (item_id) SELECT item_id FROM changed) "
                + "SELECT seq FROM changed")
        .param("id", itemId)
        .query(Long.class)
        .optional();
  }

  public record OutboxEntry(long id, String itemId) {}

  /** Item state at publish time: the latest committed version (deleted → tombstone). */
  public record PublishableItem(CatalogItemDto item, boolean deleted) {}

  /**
   * Claims the oldest outbox rows for this relay instance. Must run inside a transaction; {@code
   * SKIP LOCKED} lets several replicas relay concurrently without publishing a row twice.
   */
  public List<OutboxEntry> lockOutbox(int limit) {
    return jdbc.sql(
            "SELECT id, item_id FROM outbox ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
        .param("limit", limit)
        .query((rs, n) -> new OutboxEntry(rs.getLong("id"), rs.getString("item_id")))
        .list();
  }

  public List<PublishableItem> current(java.util.Collection<String> itemIds) {
    return jdbc.sql("SELECT * FROM items WHERE item_id = ANY(:ids)")
        .param("ids", itemIds.toArray(String[]::new))
        .query((rs, n) -> new PublishableItem(map(rs, n), rs.getBoolean("deleted")))
        .list();
  }

  public void deleteOutbox(List<Long> ids) {
    jdbc.sql("DELETE FROM outbox WHERE id = ANY(:ids)")
        .param("ids", ids.toArray(Long[]::new))
        .update();
  }

  /** [pending rows, age of the oldest in seconds]. */
  public double[] outboxStats() {
    return jdbc.sql(
            "SELECT count(*) AS n, coalesce(extract(epoch FROM now() - min(created_at)), 0) AS age FROM outbox")
        .query((rs, n) -> new double[] {rs.getLong("n"), rs.getDouble("age")})
        .single();
  }

  static CatalogItemDto map(ResultSet rs, int rowNum) throws SQLException {
    var rel = rs.getDate("release_date");
    long dur = rs.getLong("duration_ms");
    boolean durNull = rs.wasNull();
    int ev = rs.getInt("enrichment_version");
    Integer enrichmentVersion = rs.wasNull() ? null : ev;
    List<String> curatedMoods = strings(rs.getArray("mood_tags"));
    List<String> moods =
        curatedMoods.isEmpty() ? strings(rs.getArray("enriched_moods")) : curatedMoods;
    return new CatalogItemDto(
        rs.getString("item_id"),
        rs.getString("domain"),
        rs.getString("title"),
        rs.getString("creator_id"),
        rs.getString("creator_name"),
        strings(rs.getArray("genres")),
        moods,
        durNull ? null : dur,
        rel == null ? null : rel.toLocalDate(),
        rs.getBoolean("explicit"),
        strings(rs.getArray("available_regions")),
        rs.getString("description"),
        rs.getString("transcript_summary"),
        strings(rs.getArray("themes")),
        strings(rs.getArray("topics")),
        rs.getString("tone"),
        rs.getString("reading_level"),
        enrichmentVersion,
        rs.getLong("seq"),
        ts(rs.getTimestamp("created_at")),
        ts(rs.getTimestamp("updated_at")));
  }

  private static java.time.Instant ts(Timestamp t) {
    return t == null ? null : t.toInstant();
  }

  private static List<String> strings(Array a) throws SQLException {
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }
}
