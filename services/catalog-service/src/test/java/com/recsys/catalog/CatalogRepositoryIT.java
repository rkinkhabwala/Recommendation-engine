package com.recsys.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class CatalogRepositoryIT {
  @Container
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

  static CatalogRepository repo;
  static javax.sql.DataSource dataSource;

  @BeforeAll
  static void setUp() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    dataSource = ds;
    repo = new CatalogRepository(JdbcClient.create(ds));
  }

  static CatalogItemDto song(String id, String title) {
    return new CatalogItemDto(
        id,
        "song",
        title,
        "a_1",
        "Artist One",
        List.of("jazz"),
        List.of("mellow"),
        200_000L,
        LocalDate.of(2026, 9, 1),
        false,
        List.of(),
        null, // description
        null, // transcript summary
        null, // themes
        null, // topics
        null, // tone
        null, // reading level
        null, // enrichment version
        null,
        null,
        null);
  }

  @Test
  void seqIncrementsOnlyOnRealChanges() {
    var first = repo.upsert(song("s_seq", "Blue")).orElseThrow();
    assertThat(first.seq()).isEqualTo(1);
    assertThat(repo.upsert(song("s_seq", "Blue"))).isEmpty(); // idempotent retry
    var second = repo.upsert(song("s_seq", "Blue (Remastered)")).orElseThrow();
    assertThat(second.seq()).isEqualTo(2);
    assertThat(second.genres()).containsExactly("jazz");
  }

  static class RecordingSink implements OutboxRelay.Sink {
    final java.util.List<String> published =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    volatile boolean failing;

    public java.util.concurrent.CompletableFuture<?> publish(CatalogItemDto item) {
      return send(item.itemId() + "@" + item.seq());
    }

    public java.util.concurrent.CompletableFuture<?> tombstone(String itemId) {
      return send(itemId + "@deleted");
    }

    private java.util.concurrent.CompletableFuture<?> send(String what) {
      if (failing) {
        return java.util.concurrent.CompletableFuture.failedFuture(
            new RuntimeException("kafka down"));
      }
      published.add(what);
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
  }

  OutboxRelay relay(RecordingSink sink) {
    var tm = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    return new OutboxRelay(
        repo,
        sink,
        new org.springframework.transaction.support.TransactionTemplate(tm),
        1000,
        null);
  }

  @Test
  void outboxPublishesLatestVersionOnceAndSurvivesKafkaOutages() {
    var sink = new RecordingSink();
    var relay = relay(sink);
    relay.tick(); // drain anything left by other tests
    sink.published.clear();

    repo.upsert(song("s_ob", "One")).orElseThrow();
    repo.upsert(song("s_ob", "Two")).orElseThrow(); // two versions queued
    sink.failing = true;
    org.assertj.core.api.Assertions.assertThatThrownBy(relay::relayBatch)
        .isInstanceOf(IllegalStateException.class); // transaction rolled back
    assertThat(repo.outboxStats()[0]).isEqualTo(2);

    sink.failing = false;
    assertThat(relay.relayBatch()).isEqualTo(2);
    assertThat(sink.published).containsExactly("s_ob@2"); // collapsed to the latest version
    assertThat(repo.outboxStats()[0]).isZero();

    repo.markDeleted("s_ob").orElseThrow();
    relay.relayBatch();
    assertThat(sink.published).containsExactly("s_ob@2", "s_ob@deleted");
  }

  @Test
  void concurrentRelaysNeverPublishARowTwice() throws Exception {
    var sink = new RecordingSink();
    var a = relay(sink);
    var b = relay(sink);
    a.tick();
    sink.published.clear();
    for (int i = 0; i < 300; i++) {
      repo.upsert(song("s_cc" + i, "T")).orElseThrow();
    }
    try (var exec = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var f1 =
          exec.submit(
              () -> {
                while (a.relayBatch() > 0) {}
              });
      var f2 =
          exec.submit(
              () -> {
                while (b.relayBatch() > 0) {}
              });
      f1.get();
      f2.get();
    }
    assertThat(sink.published).hasSize(300).doesNotHaveDuplicates();
  }

  @Test
  void enrichmentIsIdempotentAndSurvivesCatalogReloads() {
    repo.upsert(song("s_enr", "Quiet Night")).orElseThrow();
    var e =
        new Enrichment(
            1, List.of("calm"), List.of("solitude"), List.of("night"), "reflective", null);
    var enriched = repo.applyEnrichment("s_enr", e).orElseThrow();
    assertThat(enriched.themes()).containsExactly("solitude");
    assertThat(repo.applyEnrichment("s_enr", e)).isEmpty(); // same version: no-op

    // A reload of curated data does not wipe enrichment nor create a new version.
    assertThat(repo.upsert(song("s_enr", "Quiet Night"))).isEmpty();
    var found = repo.find("s_enr").orElseThrow();
    assertThat(found.themes()).containsExactly("solitude");
    assertThat(found.moods()).containsExactly("mellow"); // curated moods win over enriched
    assertThat(found.enrichmentVersion()).isEqualTo(1);
  }
}
