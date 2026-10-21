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
  }

  private NewsFileWorker worker(boolean enabled, long settleSeconds, long maxBytes) {
    return new NewsFileWorker(new NewsFileProperties(enabled, dir.toString(), 30, settleSeconds, maxBytes), new NewsDraftRepository(jdbc), Clock.systemUTC());
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
    assertThat(row.get("HEADLINE")).isEqualTo("🏠 Tin trong nước");
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
    NewsFileWorker missing = new NewsFileWorker(new NewsFileProperties(true, dir.resolve("nope").toString(), 30, 0, 100), new NewsDraftRepository(jdbc), Clock.systemUTC());

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
}
