package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NewsSummaryWorkerTest {

  static final AtomicReference<List<NewsDraft>> NEXT = new AtomicReference<>(List.of());
  static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

  @TestConfiguration
  static class Fake {
    @Bean
    @Primary
    NewsCollector fakeCollector() {
      return max -> {
        if (FAIL.get() != null) throw FAIL.get();
        return NEXT.get();
      };
    }
  }

  @Autowired NewsSummaryWorker worker;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM news_summary");
    FAIL.set(null);
    NEXT.set(List.of());
  }

  private static NewsDraft draft(String headline) {
    return new NewsDraft(headline, "Tóm tắt " + headline + " (Nguồn: VnExpress)", LocalDate.of(2026, 10, 20), "OTHER");
  }

  @Test
  void insertsUnpublishedVietnameseDraftsForVietnamAndSkipsKnownOnes() {
    NEXT.set(List.of(draft("A"), draft("A"), draft("B")));

    assertThat(worker.runOnce()).isEqualTo(2);
    assertThat(worker.runOnce()).isEqualTo(0);

    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM news_summary ORDER BY headline");
    assertThat(rows).hasSize(2);
    for (Map<String, Object> row : rows) {
      assertThat(row.get("PUBLISHED")).isEqualTo(false);
      assertThat(row.get("PUBLISHED_AT")).isNull();
      assertThat(row.get("COUNTRY_CODE")).isEqualTo("VN");
      assertThat(row.get("LANGUAGE")).isEqualTo("vi");
      assertThat(row.get("CATEGORY")).isEqualTo("OTHER");
      assertThat(row.get("IMAGE_URL")).isNull();
      assertThat(row.get("ID").toString()).hasSize(36);
    }
  }

  @Test
  void theDraftsCategoryIsWrittenToTheRow() {
    new NewsDraftRepository(jdbc).insert(
        new NewsDraft("W", "s", LocalDate.of(2026, 10, 20), "WORLD"), java.time.Instant.parse("2026-10-20T01:00:00Z"));

    assertThat(jdbc.queryForObject("SELECT category FROM news_summary WHERE headline = 'W'", String.class)).isEqualTo("WORLD");
  }

  @Test
  void aFailedCollectionStoresNothingAndDoesNotThrow() {
    FAIL.set(new IllegalStateException("boom"));

    assertThat(worker.runOnce()).isEqualTo(-1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
  }

  @Test
  void theScheduledAndStartupEntryPointsRespectTheEnabledFlag() {
    NEXT.set(List.of(draft("A")));

    worker.scheduledRun();
    worker.runAtStartup();

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
  }

  @Test
  void whenEnabledTheScheduleAndTheStartupRunCollect() {
    NEXT.set(List.of(draft("A"), draft("B")));
    NewsSummaryWorker enabled = new NewsSummaryWorker(max -> NEXT.get(), new NewsDraftRepository(jdbc),
        new NewsWorkerProperties(true, "x", "UTC", true, 5), java.time.Clock.systemUTC());

    enabled.scheduledRun();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(2);

    jdbc.update("DELETE FROM news_summary");
    enabled.runAtStartup();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(2);
  }
}
