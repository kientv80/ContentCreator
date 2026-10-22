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
  void insertPendingStoresAPublicPendingRowWithTheGivenTime() {
    repo.insertPending("u-1", "hello", LocalDate.of(2026, 10, 22), Instant.parse("2026-10-22T01:02:03Z"));

    var row = jdbc.queryForMap("SELECT * FROM poems");
    assertThat(row.get("WRITER_ID")).isEqualTo("u-1");
    assertThat(row.get("CONTENT_TEXT")).isEqualTo("hello");
    assertThat(row.get("PRIVACY")).isEqualTo("PUBLIC");
    assertThat(row.get("APPROVAL_STATUS")).isEqualTo("PENDING");
    assertThat(row.get("LOCAL_DATE").toString()).isEqualTo("2026-10-22");
    assertThat(row.get("VOICE_URL")).isNull();
    assertThat(row.get("DELETED_AT")).isNull();
    assertThat(String.valueOf(row.get("ID"))).hasSize(36);
    assertThat(((java.sql.Timestamp) row.get("CREATED_AT")).toInstant()).isEqualTo(Instant.parse("2026-10-22T01:02:03Z"));
  }

  @Test
  void existsPostMatchesTheHeadingPrefixPerWriterAndDayInAnyState() {
    LocalDate day = LocalDate.of(2026, 10, 22);
    repo.insertPending("u-1", "Post 1: A\n\nbody", day, Instant.now());
    jdbc.update("UPDATE poems SET deleted_at = NOW()");

    assertThat(repo.existsPost("u-1", day, "Post 1: A")).isTrue();
    assertThat(repo.existsPost("u-1", day, "Post 2: B")).isFalse();
    assertThat(repo.existsPost("u-1", day.plusDays(1), "Post 1: A")).isFalse();
    assertThat(repo.existsPost("other", day, "Post 1: A")).isFalse();
  }

  @Test
  void existsPostTreatsLikeWildcardsInTheHeadingAsPlainText() {
    LocalDate day = LocalDate.of(2026, 10, 22);
    repo.insertPending("u-1", "Post 1: 100% sure_x\n\nbody", day, Instant.now());

    assertThat(repo.existsPost("u-1", day, "Post 1: 100% sure_x")).isTrue();
    assertThat(repo.existsPost("u-1", day, "Post 1: 1%")).isFalse();
    assertThat(repo.existsPost("u-1", day, "Post 1: 100% sureXx")).isFalse();
    assertThat(repo.existsPost("u-1", day, "%")).isFalse();
  }
}
