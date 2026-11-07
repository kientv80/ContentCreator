package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CollectionRunLogRepositoryTest {

  static final Instant NOON = Instant.parse("2026-11-07T05:00:00Z");

  @Autowired JdbcTemplate jdbc;
  CollectionRunLogRepository repository;

  @BeforeEach
  @AfterEach
  void clean() {
    repository = new CollectionRunLogRepository(jdbc);
    jdbc.update("DELETE FROM news_collection_run");
  }

  @Test
  void insert_storesEveryColumnOfTheRun() {
    repository.insert(new CollectionRunLogRepository.Run(NOON, "US", "HEALTH", 5, 3, 2, false, null, 1234));

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM news_collection_run");
    assertThat(row.get("COUNTRY_CODE")).isEqualTo("US");
    assertThat(row.get("CATEGORY_CODE")).isEqualTo("HEALTH");
    assertThat(row.get("STORIES")).isEqualTo(5);
    assertThat(row.get("STORED")).isEqualTo(3);
    assertThat(row.get("REJECTED")).isEqualTo(2);
    assertThat(row.get("FAILED")).isEqualTo(false);
    assertThat(row.get("ERROR")).isNull();
    assertThat(row.get("DURATION_MS")).isEqualTo(1234);
  }

  @Test
  void insert_cutsAnErrorTextToOneHundredTwentyCharacters() {
    repository.insert(new CollectionRunLogRepository.Run(NOON, "US", "HEALTH", 0, 0, 0, true, "E".repeat(300), 1));

    String error = jdbc.queryForObject("SELECT error FROM news_collection_run", String.class);
    assertThat(error).hasSize(120);
  }

  @Test
  void insert_keepsAShortErrorStatusAsIs() {
    repository.insert(new CollectionRunLogRepository.Run(NOON, "US", "HEALTH", 0, 0, 0, true, "KEY_MISSING", 1));

    assertThat(jdbc.queryForObject("SELECT error FROM news_collection_run", String.class)).isEqualTo("KEY_MISSING");
    assertThat(jdbc.queryForObject("SELECT failed FROM news_collection_run", Boolean.class)).isTrue();
  }

  @Test
  void countCallsSince_respectsTheVietnamDayBoundary() {
    // Vietnam day of 2026-11-07 starts at 2026-11-06T17:00:00Z (UTC+7).
    Instant start = Instant.parse("2026-11-06T17:00:00Z");
    repository.insert(new CollectionRunLogRepository.Run(start.minusSeconds(1), "US", "HEALTH", 0, 0, 0, false, null, 1));
    repository.insert(new CollectionRunLogRepository.Run(start, "US", "HEALTH", 0, 0, 0, false, null, 1));
    repository.insert(new CollectionRunLogRepository.Run(start.plusSeconds(3600), "GB", "WORLD", 0, 0, 0, false, null, 1));

    assertThat(repository.countCallsSince(start)).isEqualTo(2);
    assertThat(repository.countCallsSince(start.minusSeconds(5))).isEqualTo(3);
    assertThat(repository.countCallsSince(start.plusSeconds(7200))).isZero();
  }
}
