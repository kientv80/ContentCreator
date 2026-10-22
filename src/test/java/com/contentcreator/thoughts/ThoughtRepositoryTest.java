package com.contentcreator.thoughts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ThoughtRepositoryTest {

  @Autowired JdbcTemplate jdbc;
  ThoughtRepository repo;

  @BeforeEach
  void reset() {
    repo = new ThoughtRepository(jdbc);
    jdbc.update("DELETE FROM poems");
    jdbc.update("DELETE FROM users");
    jdbc.update("INSERT INTO users (id, username) VALUES ('u-1', 'elderfriend_ai_vn')");
  }

  @Test
  void findWriterIdReturnsTheIdOrEmpty() {
    assertThat(repo.findWriterId("elderfriend_ai_vn")).contains("u-1");
    assertThat(repo.findWriterId("nobody")).isEmpty();
  }

  @Test
  void insertPendingStoresAPublicPendingRowAndExistsSeesIt() {
    LocalDate day = LocalDate.of(2026, 10, 22);
    assertThat(repo.exists("u-1", day)).isFalse();

    repo.insertPending("u-1", "hello", day, Instant.parse("2026-10-22T01:02:03Z"));

    var row = jdbc.queryForMap("SELECT * FROM poems");
    assertThat(row.get("WRITER_ID")).isEqualTo("u-1");
    assertThat(row.get("CONTENT_TEXT")).isEqualTo("hello");
    assertThat(row.get("PRIVACY")).isEqualTo("PUBLIC");
    assertThat(row.get("APPROVAL_STATUS")).isEqualTo("PENDING");
    assertThat(row.get("LOCAL_DATE").toString()).isEqualTo("2026-10-22");
    assertThat(row.get("VOICE_URL")).isNull();
    assertThat(row.get("DELETED_AT")).isNull();
    assertThat(String.valueOf(row.get("ID"))).hasSize(36);
    assertThat(repo.exists("u-1", day)).isTrue();
    assertThat(repo.exists("u-1", day.plusDays(1))).isFalse();
    assertThat(repo.exists("other", day)).isFalse();
  }
}
