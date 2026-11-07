package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

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
  void seen_isTrueOnlyForTheSameCountryAndHash() {
    jdbc.update("INSERT INTO news_story_source (country_code, url_hash, url) VALUES ('US', ?, 'https://a.test/x')", HASH);
    JdbcUrlSeenChecker checker = new JdbcUrlSeenChecker(jdbc);

    assertThat(checker.seen("US", HASH)).isTrue();
    assertThat(checker.seen("GB", HASH)).isFalse();
    assertThat(checker.seen("US", "b".repeat(64))).isFalse();
  }
}
