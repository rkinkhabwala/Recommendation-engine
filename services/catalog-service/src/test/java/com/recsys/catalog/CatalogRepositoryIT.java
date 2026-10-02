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

  @BeforeAll
  static void setUp() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
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
        null,
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

  @Test
  void unpublishedVersionsAreFoundUntilMarked() {
    var stored = repo.upsert(song("s_pub", "Red")).orElseThrow();
    assertThat(repo.unpublished(100)).anyMatch(u -> u.item().itemId().equals("s_pub"));
    repo.markPublished("s_pub", stored.seq());
    assertThat(repo.unpublished(100)).noneMatch(u -> u.item().itemId().equals("s_pub"));

    long delSeq = repo.markDeleted("s_pub").orElseThrow();
    assertThat(delSeq).isEqualTo(stored.seq() + 1);
    assertThat(repo.find("s_pub")).isEmpty();
    assertThat(repo.unpublished(100))
        .anyMatch(u -> u.item().itemId().equals("s_pub") && u.deleted());
  }
}
