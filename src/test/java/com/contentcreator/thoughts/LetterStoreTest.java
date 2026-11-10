package com.contentcreator.thoughts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LetterStoreTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void insertsLastPostFirstAndSkipsAHeadingAlreadyStoredThatDay() {
    jdbc.update("DELETE FROM poems");
    jdbc.update("DELETE FROM users");
    jdbc.update("INSERT INTO users (id, username) VALUES ('w-1', 'fake_writer')");
    var store = new LetterStore(new ThoughtRepository(jdbc));
    LocalDate day = LocalDate.of(2026, 11, 10);
    List<String> posts = List.of("One\n\nbody one", "Two\n\nbody two");

    assertThat(store.store("w-1", day, posts, Instant.parse("2026-11-10T03:00:00Z"))).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT content_text FROM poems ORDER BY created_at DESC", String.class).get(0)).startsWith("One");
    assertThat(store.store("w-1", day, posts, Instant.parse("2026-11-10T04:00:00Z"))).isZero();
    assertThat(store.store("w-1", day, List.of("Two\n\nbody two", "Three\n\nx"), Instant.now())).isEqualTo(1);
  }
}
