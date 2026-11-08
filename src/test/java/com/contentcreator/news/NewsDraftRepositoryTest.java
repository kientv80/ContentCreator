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
    jdbc.update("DELETE FROM news_story_source");
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

  private static StoryUrl url(String n) {
    return new StoryUrl(String.format("%064d", Integer.parseInt(n)), "https://example.test/" + n, String.format("%064d", 1000 + Integer.parseInt(n)));
  }

  @Test
  void insertWritesCountryLanguageAndUnpublishedFromTheArguments() {
    boolean stored = new NewsDraftRepository(jdbc).insert(draft(List.of()), "US", "en", NOW, List.of());

    assertThat(stored).isTrue();
    var row = jdbc.queryForMap("SELECT * FROM news_summary");
    assertThat(row).containsEntry("COUNTRY_CODE", "US").containsEntry("LANGUAGE", "en").containsEntry("PUBLISHED", false);
    assertThat(row.get("PUBLISHED_AT")).isNull();
  }

  @Test
  void insertWritesOneSourceRowPerUrlWithCountryHashUrlAndSummaryId() {
    new NewsDraftRepository(jdbc).insert(draft(List.of()), "US", "en", NOW, List.of(url("1"), url("2")));

    String id = jdbc.queryForObject("SELECT id FROM news_summary", String.class);
    var rows = jdbc.queryForList("SELECT * FROM news_story_source ORDER BY url_hash");
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0)).containsEntry("COUNTRY_CODE", "US").containsEntry("URL_HASH", url("1").hash())
        .containsEntry("URL", "https://example.test/1").containsEntry("SUMMARY_ID", id);
  }

  @Test
  void insertTruncatesAnOverlongUrlToTheColumnLimit() {
    StoryUrl longUrl = new StoryUrl(url("3").hash(), "https://example.test/" + "a".repeat(2500), null);

    new NewsDraftRepository(jdbc).insert(draft(List.of()), "US", "en", NOW, List.of(longUrl));

    assertThat(jdbc.queryForObject("SELECT url FROM news_story_source", String.class)).hasSize(NewsDraftRepository.MAX_URL);
  }

  @Test
  void aUrlAlreadyCollectedForTheCountryStoresNothingAndReportsFalse() {
    NewsDraftRepository repo = new NewsDraftRepository(jdbc);
    repo.insert(new NewsDraft("First", "s", LocalDate.of(2026, 10, 24), "OTHER", List.of()), "US", "en", NOW, List.of(url("1")));

    boolean second = repo.insert(new NewsDraft("Second", "s", LocalDate.of(2026, 10, 24), "OTHER",
        List.of(new StoryExtra(0, "A", List.of("p"), null))), "US", "en", NOW, List.of(url("2"), url("1")));

    assertThat(second).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source", Integer.class)).isEqualTo(1);
  }

  @Test
  void theSameUrlIsAllowedForAnotherCountry() {
    NewsDraftRepository repo = new NewsDraftRepository(jdbc);
    repo.insert(draft(List.of()), "US", "en", NOW, List.of(url("1")));

    assertThat(repo.insert(new NewsDraft("Other", "s", LocalDate.of(2026, 10, 24), "OTHER"), "GB", "en", NOW, List.of(url("1")))).isTrue();
  }

  @Test
  void anOtherFailureRollsBackTheSummaryAndIsThrown() {
    NewsDraft bad = new NewsDraft("H", "x".repeat(9000), LocalDate.of(2026, 10, 24), "OTHER");

    assertThatThrownBy(() -> new NewsDraftRepository(jdbc).insert(bad, "US", "en", NOW, List.of(url("1"))))
        .isInstanceOf(RuntimeException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source", Integer.class)).isZero();
  }

  @Test
  void deletingADraftKeepsItsUrlRowWithoutASummaryId() {
    new NewsDraftRepository(jdbc).insert(draft(List.of()), "US", "en", NOW, List.of(url("1")));
    jdbc.update("DELETE FROM news_summary");

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT summary_id FROM news_story_source", String.class)).isNull();
  }

  @Test
  void theShortInsertStillStoresVietnamInVietnameseWithNoUrlRows() {
    new NewsDraftRepository(jdbc).insert(draft(List.of()), NOW);

    assertThat(jdbc.queryForMap("SELECT country_code, language FROM news_summary")).containsEntry("COUNTRY_CODE", "VN").containsEntry("LANGUAGE", "vi");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source", Integer.class)).isZero();
  }

  @Test
  void existsWithACountryOnlyMatchesThatCountry() {
    NewsDraftRepository repo = new NewsDraftRepository(jdbc);
    repo.insert(draft(List.of()), "US", "en", NOW, List.of());

    assertThat(repo.exists(draft(List.of()), "US")).isTrue();
    assertThat(repo.exists(draft(List.of()), "GB")).isFalse();
    assertThat(repo.exists(draft(List.of()))).isFalse(); // the one-argument form means Vietnam
  }

  @Test
  void insertWritesTheTitleHashOfEachSourceRowAndNullWhenNone() {
    new NewsDraftRepository(jdbc).insert(draft(List.of()), "US", "en", NOW, List.of(url("1"), new StoryUrl(url("2").hash(), "https://example.test/2", null)));

    var rows = jdbc.queryForList("SELECT * FROM news_story_source ORDER BY url_hash");
    assertThat(rows.get(0)).containsEntry("TITLE_HASH", url("1").titleHash());
    assertThat(rows.get(1).get("TITLE_HASH")).isNull();
  }
}
