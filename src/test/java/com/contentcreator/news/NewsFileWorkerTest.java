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
}
