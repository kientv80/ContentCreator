package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NewsFileWorkerTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired NewsFileWorker disabledWorker;
  @TempDir Path dir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM news_summary");
    jdbc.update("DELETE FROM news_category WHERE code IN ('HEALTH', 'OFF')");
  }

  private NewsFileWorker worker(boolean enabled, long settleSeconds, long maxBytes) {
    return new NewsFileWorker(new NewsFileProperties(enabled, dir.toString(), 30, settleSeconds, maxBytes), new NewsDraftRepository(jdbc), new CategoryRepository(jdbc), Clock.systemUTC());
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class);
  }

  private static String bulletin(String title) {
    return "{\"date\":\"2026-10-20\",\"sections\":{\"domestic\":[{\"title\":\"" + title + "\",\"summary\":\"Tóm tắt\",\"source\":\"VnExpress\"}]}}";
  }

  @Test
  void aNewFileBecomesUnpublishedVietnamDraftsAndMovesToProcessed() throws IOException {
    Files.writeString(dir.resolve("ban-tin.json"), bulletin("A"));

    assertThat(worker(true, 0, 100000).scanOnce()).isEqualTo(1);

    var row = jdbc.queryForMap("SELECT * FROM news_summary");
    assertThat(row.get("HEADLINE")).isEqualTo("Tin trong nước");
    assertThat(row.get("SUMMARY")).isEqualTo("**A**: Tóm tắt (Nguồn: VnExpress)");
    assertThat(row.get("PUBLISHED")).isEqualTo(false);
    assertThat(row.get("COUNTRY_CODE")).isEqualTo("VN");
    assertThat(row.get("LANGUAGE")).isEqualTo("vi");
    assertThat(row.get("CATEGORY")).isEqualTo("DOMESTIC");
    assertThat(dir.resolve("ban-tin.json")).doesNotExist();
    try (var done = Files.list(dir.resolve("processed"))) {
      assertThat(done.count()).isEqualTo(1);
    }
  }

  @Test
  void aKnownStoryIsNotInsertedTwice_andOtherFilesAreIgnored() throws IOException {
    Files.writeString(dir.resolve("one.json"), bulletin("A"));
    Files.writeString(dir.resolve("two.JSON"), bulletin("A"));
    Files.writeString(dir.resolve("notes.txt"), "ignore me");
    Files.createDirectory(dir.resolve("sub.json"));

    assertThat(worker(true, 0, 100000).scanOnce()).isEqualTo(1);

    assertThat(count()).isEqualTo(1);
    assertThat(dir.resolve("notes.txt")).exists();
    assertThat(dir.resolve("sub.json")).isDirectory();
  }

  @Test
  void anUnreadableOrOversizedFileMovesToFailedAndStoresNothing() throws IOException {
    Files.writeString(dir.resolve("bad.json"), "{ not json");
    Files.writeString(dir.resolve("big.json"), bulletin("A"));

    assertThat(worker(true, 0, 20).scanOnce()).isZero();

    assertThat(count()).isZero();
    try (var failed = Files.list(dir.resolve("failed"))) {
      assertThat(failed.count()).isEqualTo(2);
    }
  }

  @Test
  void aFileStillBeingWrittenIsLeftForTheNextScan() throws IOException {
    Files.writeString(dir.resolve("new.json"), bulletin("A"));

    assertThat(worker(true, 3600, 100000).scanOnce()).isZero();

    assertThat(dir.resolve("new.json")).exists();
    assertThat(count()).isZero();
  }

  @Test
  void aSymbolicLinkIsNeverFollowed() throws IOException {
    Path outside = Files.createTempFile("outside", ".json");
    Files.writeString(outside, bulletin("Secret"));
    Files.createSymbolicLink(dir.resolve("link.json"), outside);

    assertThat(worker(true, 0, 100000).scanOnce()).isZero();

    assertThat(count()).isZero();
    Files.delete(outside);
  }

  @Test
  void aMissingFolderAndTheDisabledScheduleDoNothing() throws IOException {
    Files.writeString(dir.resolve("a.json"), bulletin("A"));
    NewsFileWorker missing = new NewsFileWorker(new NewsFileProperties(true, dir.resolve("nope").toString(), 30, 0, 100), new NewsDraftRepository(jdbc), new CategoryRepository(jdbc), Clock.systemUTC());

    assertThat(missing.scanOnce()).isZero();
    disabledWorker.scheduledScan();
    worker(false, 0, 100000).scheduledScan();

    assertThat(dir.resolve("a.json")).exists();
    worker(true, 0, 100000).scheduledScan();
    assertThat(count()).isEqualTo(1);
  }

  private int failedCount() throws IOException {
    try (var failed = Files.list(dir.resolve("failed"))) {
      return (int) failed.count();
    }
  }

  @Test
  void aFileWithAnotherHardLinkIsRefusedAndMovedToFailed() throws IOException {
    Path outside = Files.createTempFile("outside", ".json");
    Files.writeString(outside, bulletin("Secret"));
    Files.createLink(dir.resolve("hard.json"), outside);

    assertThat(worker(true, 0, 100000).scanOnce()).isZero();

    assertThat(count()).isZero();
    assertThat(dir.resolve("hard.json")).doesNotExist();
    assertThat(failedCount()).isEqualTo(1);
    assertThat(outside).exists();
    Files.delete(outside);
  }

  @Test
  void aFileExactlyAtTheLimitIsReadAndOneByteOverIsRefused() throws IOException {
    String text = bulletin("A");
    int size = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    Files.writeString(dir.resolve("exact.json"), text);

    assertThat(worker(true, 0, size).scanOnce()).isEqualTo(1);

    Files.writeString(dir.resolve("over.json"), bulletin("B"));
    assertThat(worker(true, 0, size - 1).scanOnce()).isZero();
    assertThat(failedCount()).isEqualTo(1);
  }

  @Test
  void aSymbolicLinkAsProcessedOrFailedFolderStopsTheScan() throws IOException {
    Path elsewhere = Files.createTempDirectory("elsewhere");
    Files.createSymbolicLink(dir.resolve("processed"), elsewhere);
    Files.writeString(dir.resolve("a.json"), bulletin("A"));

    assertThat(worker(true, 0, 100000).scanOnce()).isZero();

    assertThat(dir.resolve("a.json")).exists();
    assertThat(count()).isZero();
    try (var moved = Files.list(elsewhere)) {
      assertThat(moved.count()).isZero();
    }
    Files.delete(dir.resolve("processed"));
    Files.createSymbolicLink(dir.resolve("failed"), elsewhere);
    assertThat(worker(true, 0, 100000).scanOnce()).isZero();
    Files.delete(dir.resolve("failed"));
    Files.delete(elsewhere);
  }

  @Test
  void aRegularFileAsProcessedFolderStopsTheScan_andMissingFoldersAreFine() throws IOException {
    assertThat(NewsFileWorker.folderIsSafe(dir.resolve("none"))).isTrue();
    Files.writeString(dir.resolve("failed"), "x");
    Files.writeString(dir.resolve("a.json"), bulletin("A"));

    assertThat(worker(true, 0, 100000).scanOnce()).isZero();

    assertThat(dir.resolve("a.json")).exists();
    assertThat(NewsFileWorker.folderIsSafe(dir.resolve("failed"))).isFalse();
  }

  @Test
  void linkCountIsOneForAPlainFile() throws IOException {
    Path f = Files.writeString(dir.resolve("p.txt"), "x");
    assertThat(NewsFileWorker.linkCount(f)).isEqualTo(1);
    Files.createLink(dir.resolve("q.txt"), f);
    assertThat(NewsFileWorker.linkCount(f)).isEqualTo(2);
  }

  private String sectionsBulletin() {
    return "{\"date\":\"2026-10-20\",\"sections\":{\"health\":[{\"summary\":\"a\"}],\"off\":[{\"summary\":\"b\"}],"
        + "\"world\":[{\"summary\":\"c\"}],\"mystery\":[{\"summary\":\"d\"}]}}";
  }

  private java.util.Map<String, String> categoriesByHeadline() {
    var map = new java.util.HashMap<String, String>();
    jdbc.query("SELECT headline, category FROM news_summary", rs -> { map.put(rs.getString(1), rs.getString(2)); });
    return map;
  }

  @Test
  void aNewActiveCategoryHeadsItsDraft_whileInactiveAndUnknownSectionsFallBackToOther() throws IOException {
    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('HEALTH', '🩺', 'Health', 'Sức khỏe', 6, TRUE)");
    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('OFF', '🚫', 'Off', 'Tắt', 7, FALSE)");
    Files.writeString(dir.resolve("a.json"), sectionsBulletin());

    assertThat(worker(true, 0, 100000).scanOnce()).isEqualTo(4);

    assertThat(categoriesByHeadline()).containsEntry("Tin sức khỏe", "HEALTH").containsEntry("Tin thế giới", "WORLD")
        .containsEntry("Tin off", "OTHER").containsEntry("Tin mystery", "OTHER").hasSize(4);
  }

  @Test
  void whenTheCategoryLookupFailsEveryDraftIsOtherAndTheRunContinues() throws IOException {
    JdbcTemplate failing = org.mockito.Mockito.spy(jdbc);
    org.mockito.Mockito.doThrow(new org.springframework.jdbc.BadSqlGrammarException("q", "SELECT", new java.sql.SQLException("denied")))
        .when(failing).query(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class), org.mockito.ArgumentMatchers.any(Object[].class));
    NewsFileWorker w = new NewsFileWorker(new NewsFileProperties(true, dir.toString(), 30, 0, 100000), new NewsDraftRepository(jdbc), new CategoryRepository(failing), Clock.systemUTC());
    Files.writeString(dir.resolve("a.json"), sectionsBulletin());

    assertThat(w.scanOnce()).isEqualTo(4);

    assertThat(categoriesByHeadline()).containsEntry("Tin thế giới", "OTHER").containsEntry("Tin health", "OTHER").hasSize(4);
    assertThat(dir.resolve("failed")).doesNotExist();
    assertThat(dir.resolve("a.json")).doesNotExist();
  }

  @Test
  void theNewestRealBulletinStoresDraftsWithTheirStoryExtras() throws IOException {
    try (var in = NewsFileWorkerTest.class.getResourceAsStream("/bulletin/ban-tin-2026-10-24.json")) {
      Files.write(dir.resolve("ban-tin.json"), in.readAllBytes());
    }

    assertThat(worker(true, 0, 1_000_000).scanOnce()).isEqualTo(4);

    var domestic = jdbc.queryForMap("SELECT id, summary FROM news_summary WHERE category = 'DOMESTIC'");
    var extras = jdbc.queryForList("SELECT * FROM news_story_extra WHERE summary_id = ? ORDER BY story_index", domestic.get("ID"));
    String[] paragraphs = domestic.get("SUMMARY").toString().split("\n\n");
    assertThat(extras).hasSize(paragraphs.length);
    for (var e : extras) {
      assertThat(paragraphs[(Integer) e.get("STORY_INDEX")]).startsWith("**" + e.get("STORY_TITLE") + "**");
    }
    assertThat(extras.get(0).get("KEY_POINTS").toString().split("\n")).hasSize(6);
    assertThat(extras.get(0).get("ANALYSIS").toString()).hasSize(334);
    assertThat(extras.get(1).get("KEY_POINTS").toString().split("\n")).hasSize(5);
    assertThat(extras.get(1).get("ANALYSIS").toString()).hasSize(224);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isEqualTo(22);
  }

  @Test
  void aKnownDraftIsSkippedWithItsExtras() throws IOException {
    String file = "{\"date\":\"2026-10-20\",\"sections\":{\"domestic\":[{\"title\":\"A\",\"summary\":\"s\",\"key_points\":[\"k\"]}]}}";
    Files.writeString(dir.resolve("one.json"), file);
    Files.writeString(dir.resolve("two.json"), file);

    assertThat(worker(true, 0, 100000).scanOnce()).isEqualTo(1);

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isEqualTo(1);
  }

  @Test
  void aFailingExtraInsertRollsTheDraftBackAndLeavesTheFileForTheNextScan() throws IOException {
    Files.writeString(dir.resolve("one.json"), "{\"date\":\"2026-10-20\",\"sections\":{\"domestic\":[{\"title\":\"A\",\"summary\":\"s\",\"key_points\":[\"k\"]}]}}");
    jdbc.update("ALTER TABLE news_story_extra ADD CONSTRAINT chk_fail CHECK (story_title <> 'A')");
    try {
      assertThat(worker(true, 0, 100000).scanOnce()).isZero();
    } finally {
      jdbc.update("ALTER TABLE news_story_extra DROP CONSTRAINT chk_fail");
    }

    assertThat(count()).isZero();
    assertThat(dir.resolve("one.json")).exists();
    assertThat(dir.resolve("failed")).doesNotExist();
  }
}
