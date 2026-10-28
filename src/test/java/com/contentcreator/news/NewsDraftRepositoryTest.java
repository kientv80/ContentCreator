package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NewsDraftRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-10-24T01:00:00Z");

  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM news_summary");
  }

  private NewsDraft draft(List<StoryExtra> extras) {
    return new NewsDraft("H", "summary", LocalDate.of(2026, 10, 24), "DOMESTIC", extras);
  }

  @Test
  void insertWritesTheSummaryAndItsExtrasWithIndexTitleAndText() {
    new NewsDraftRepository(jdbc).insert(draft(List.of(
        new StoryExtra(0, "First", List.of("p1", "p2"), "analysis one"),
        new StoryExtra(2, "Third", List.of(), "only analysis"),
        new StoryExtra(3, "Fourth", List.of("only point"), null))), NOW);

    String id = jdbc.queryForObject("SELECT id FROM news_summary", String.class);
    var rows = jdbc.queryForList("SELECT * FROM news_story_extra WHERE summary_id = ? ORDER BY story_index", id);
    assertThat(rows).hasSize(3);
    assertThat(rows.get(0)).containsEntry("STORY_INDEX", 0).containsEntry("STORY_TITLE", "First")
        .containsEntry("KEY_POINTS", "p1\np2").containsEntry("ANALYSIS", "analysis one");
    assertThat(rows.get(1)).containsEntry("STORY_INDEX", 2).containsEntry("KEY_POINTS", null)
        .containsEntry("ANALYSIS", "only analysis");
    assertThat(rows.get(2)).containsEntry("STORY_INDEX", 3).containsEntry("KEY_POINTS", "only point")
        .containsEntry("ANALYSIS", null);
    assertThat(rows.get(0).get("CREATED_AT")).isNotNull();
  }

  @Test
  void aDraftWithoutExtrasWritesOnlyTheSummary() {
    new NewsDraftRepository(jdbc).insert(new NewsDraft("H", "s", LocalDate.of(2026, 10, 24), "OTHER"), NOW);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isZero();
  }

  @Test
  void aFailingExtraRollsBackTheSummaryAndEarlierExtras() {
    NewsDraft bad = draft(List.of(new StoryExtra(0, "A", List.of("p"), null), new StoryExtra(0, "Same index", List.of("q"), null)));

    assertThatThrownBy(() -> new NewsDraftRepository(jdbc).insert(bad, NOW)).isInstanceOf(RuntimeException.class);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isZero();
  }

  @Test
  void deletingTheSummaryRemovesItsExtras() {
    new NewsDraftRepository(jdbc).insert(draft(List.of(new StoryExtra(0, "A", List.of("p"), null))), NOW);
    jdbc.update("DELETE FROM news_summary");

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isZero();
  }

  @Test
  void existsIgnoresExtrasAndMatchesHeadlineAndDay() {
    NewsDraftRepository repo = new NewsDraftRepository(jdbc);
    repo.insert(draft(List.of(new StoryExtra(0, "A", List.of("p"), null))), NOW);

    assertThat(repo.exists(draft(List.of()))).isTrue();
  }
}
