package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class JdbcUrlSeenCheckerTest {

  static final String HASH = "a".repeat(64);

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM news_story_source");
  }

  @Test
  void seenUrl_isTrueOnlyForTheSameCountryAndHash() {
    jdbc.update("INSERT INTO news_story_source (country_code, url_hash, url) VALUES ('US', ?, 'https://a.test/x')", HASH);
    JdbcUrlSeenChecker checker = new JdbcUrlSeenChecker(jdbc);

    assertThat(checker.seenUrl("US", HASH)).isTrue();
    assertThat(checker.seenUrl("GB", HASH)).isFalse();
    assertThat(checker.seenUrl("US", "b".repeat(64))).isFalse();
  }

  @Test
  void seenTitle_isTrueOnlyForTheSameCountryHashAndWithinTheWindow() {
    Instant now = Instant.parse("2026-11-08T10:00:00Z");
    jdbc.update("INSERT INTO news_story_source (country_code, url_hash, url, title_hash, created_at) VALUES ('US', ?, 'https://a.test/x', ?, ?)",
        HASH, "t".repeat(64), java.sql.Timestamp.from(now.minusSeconds(6 * 86400)));
    JdbcUrlSeenChecker checker = new JdbcUrlSeenChecker(jdbc);

    assertThat(checker.seenTitle("US", "t".repeat(64), now.minusSeconds(7 * 86400))).isTrue();
    assertThat(checker.seenTitle("US", "t".repeat(64), now.minusSeconds(5 * 86400))).isFalse();
    assertThat(checker.seenTitle("GB", "t".repeat(64), now.minusSeconds(7 * 86400))).isFalse();
    assertThat(checker.seenTitle("US", "u".repeat(64), now.minusSeconds(7 * 86400))).isFalse();
  }
}
