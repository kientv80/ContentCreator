package com.contentcreator.thoughts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ShareThoughtWorkerTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired ShareThoughtWorker disabledWorker;
  @TempDir Path dir;
  @TempDir Path outer;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM poems");
    jdbc.update("DELETE FROM users");
    jdbc.update("INSERT INTO users (id, username) VALUES ('vn-id', 'elderfriend_ai_vn'), ('us-id', 'elderfriend_ai_us')");
  }

  private ShareThoughtWorker worker(boolean enabled, long settleSeconds, long maxBytes) {
    var props = new ThoughtProperties(enabled, dir.toString(), 60, settleSeconds, maxBytes, new ThoughtProperties.Accounts("elderfriend_ai_vn", "elderfriend_ai_us"));
    return new ShareThoughtWorker(props, new ThoughtRepository(jdbc), Clock.systemUTC());
  }

  private ShareThoughtWorker worker() {
    return worker(true, 0, 200000);
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM poems", Integer.class);
  }

  private long filesIn(String folder) throws IOException {
    if (!Files.exists(dir.resolve(folder))) {
      return 0;
    }
    try (var s = Files.list(dir.resolve(folder))) {
      return s.count();
    }
  }

  private void copyLetter(String name) throws IOException {
    Files.copy(Path.of("src/test/resources/letters", name), dir.resolve(name));
  }

  @Test
  void theRealLettersGoToTheirOwnLanguageAccountsAsPublicPending() throws IOException {
    copyLetter("2026-10-22-vi.md");
    copyLetter("2026-10-22-en.md");

    assertThat(worker().scanOnce()).isEqualTo(4);

    var vi = jdbc.queryForMap("SELECT * FROM poems WHERE writer_id = 'vn-id' AND content_text LIKE 'Bài 1%'");
    var en = jdbc.queryForMap("SELECT * FROM poems WHERE writer_id = 'us-id' AND content_text LIKE 'Post 1%'");
    assertThat(count()).isEqualTo(4);
    assertThat(vi.get("PRIVACY")).isEqualTo("PUBLIC");
    assertThat(vi.get("APPROVAL_STATUS")).isEqualTo("PENDING");
    assertThat(vi.get("LOCAL_DATE").toString()).isEqualTo("2026-10-22");
    assertThat((String) vi.get("CONTENT_TEXT")).startsWith("Bài 1: ");
    assertThat((String) en.get("CONTENT_TEXT")).doesNotContain("**");
    assertThat(filesIn("processed")).isEqualTo(2);
    assertThat(dir.resolve("2026-10-22-vi.md")).doesNotExist();
    assertThat((String) en.get("CONTENT_TEXT")).startsWith("Post 1: ");
  }

  @Test
  void aMissingAccountLeavesTheFileInPlace() throws IOException {
    jdbc.update("DELETE FROM users WHERE username = 'elderfriend_ai_us'");
    copyLetter("2026-10-22-vi.md");
    copyLetter("2026-10-22-en.md");

    assertThat(worker().scanOnce()).isEqualTo(2);

    assertThat(dir.resolve("2026-10-22-en.md")).exists();
    assertThat(filesIn("failed")).isZero();
    assertThat(count()).isEqualTo(2);
  }

  private void insertOld(String id, String text, String extra) {
    jdbc.update("INSERT INTO poems (id, writer_id, content_text, privacy, approval_status, local_date, created_at, updated_at" + (extra.isEmpty() ? "" : ", deleted_at")
        + ") VALUES ('" + id + "', 'vn-id', ?, 'PRIVATE', 'REJECTED', DATE '2026-10-22', NOW(), NOW()" + (extra.isEmpty() ? "" : ", NOW()") + ")", text);
  }

  private String heading(String letter, int n) throws IOException {
    return LetterText.splitPosts(Files.readString(Path.of("src/test/resources/letters", letter))).get(n).lines().findFirst().orElseThrow();
  }

  @Test
  void reRunningALetterStoresNothingNew() throws IOException {
    copyLetter("2026-10-22-vi.md");
    assertThat(worker().scanOnce()).isEqualTo(2);
    Files.move(dir.resolve("processed").toFile().listFiles()[0].toPath(), dir.resolve("2026-10-22-vi.md"));

    assertThat(worker().scanOnce()).isZero();

    assertThat(count()).isEqualTo(2);
    assertThat(dir.resolve("2026-10-22-vi.md")).doesNotExist();
  }

  @Test
  void aPostAlreadyStoredEvenDeletedIsSkippedAndOnlyTheOtherIsStored() throws IOException {
    insertOld("p1", heading("2026-10-22-vi.md", 0) + "\n\nold body", "deleted");
    copyLetter("2026-10-22-vi.md");

    assertThat(worker().scanOnce()).isEqualTo(1);

    assertThat(count()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poems WHERE content_text LIKE 'Bài 2%'", Integer.class)).isEqualTo(1);
    assertThat(filesIn("processed")).isEqualTo(1);
  }

  @Test
  void aThoughtOfAnotherTextOnTheSameDayDoesNotBlockThePosts() throws IOException {
    insertOld("p1", "something else", "");
    copyLetter("2026-10-22-vi.md");

    assertThat(worker().scanOnce()).isEqualTo(2);
  }

  @Test
  void post1GetsTheLatestCreatedAtSoItListsFirstInTheFeed() throws IOException {
    copyLetter("2026-10-22-en.md");
    worker().scanOnce();

    var rows = jdbc.queryForList("SELECT content_text, created_at FROM poems ORDER BY created_at DESC");
    assertThat((String) rows.get(0).get("CONTENT_TEXT")).startsWith("Post 1: ");
    assertThat((String) rows.get(1).get("CONTENT_TEXT")).startsWith("Post 2: ");
    long gap = ((java.sql.Timestamp) rows.get(0).get("CREATED_AT")).getTime() - ((java.sql.Timestamp) rows.get(1).get("CREATED_AT")).getTime();
    assertThat(gap).isEqualTo(1000);
  }

  @Test
  void aFailureMidFileLeavesTheFileAndTheRetryStoresTheRestWithoutDuplicates() throws IOException {
    copyLetter("2026-10-22-en.md");
    jdbc.execute("ALTER TABLE poems ADD CONSTRAINT chk_p1 CHECK (content_text NOT LIKE 'Post 1%')");
    try {
      assertThat(worker().scanOnce()).isZero();
      assertThat(dir.resolve("2026-10-22-en.md")).exists();
      assertThat(count()).isEqualTo(1);
    } finally {
      jdbc.execute("ALTER TABLE poems DROP CONSTRAINT chk_p1");
    }

    assertThat(worker().scanOnce()).isEqualTo(1);

    assertThat(count()).isEqualTo(2);
    assertThat(dir.resolve("2026-10-22-en.md")).doesNotExist();
    assertThat(filesIn("processed")).isEqualTo(1);
  }

  @Test
  void aLetterWithoutSectionsIsOnePost() throws IOException {
    Files.writeString(dir.resolve("2026-10-22-en.md"), "just a note");

    assertThat(worker().scanOnce()).isEqualTo(1);

    assertThat(jdbc.queryForObject("SELECT content_text FROM poems", String.class)).isEqualTo("just a note");
  }

  @Test
  void otherNamesAreSkippedAndNonMarkdownFilesAndFoldersIgnored() throws IOException {
    Files.writeString(dir.resolve("2026-10-21.md"), "plain");
    Files.writeString(dir.resolve("2026-10-21-fr.md"), "fr");
    Files.writeString(dir.resolve("2026-13-40-vi.md"), "bad date");
    Files.writeString(dir.resolve("notes.txt"), "ignore");
    Files.createDirectory(dir.resolve("sub.md"));

    assertThat(worker().scanOnce()).isZero();

    assertThat(filesIn("skipped")).isEqualTo(3);
    assertThat(dir.resolve("notes.txt")).exists();
    assertThat(dir.resolve("sub.md")).isDirectory();
    assertThat(count()).isZero();
  }

  @Test
  void emptyAfterCleaningOrOversizedOrUncuttableFilesGoToFailed() throws IOException {
    Files.writeString(dir.resolve("2026-10-20-vi.md"), "---\n\n---\n");
    Files.writeString(dir.resolve("2026-10-21-vi.md"), "y".repeat(6001));
    Files.writeString(dir.resolve("2026-10-22-vi.md"), "hello hello hello");

    assertThat(worker(true, 0, 10).scanOnce()).isZero();

    assertThat(filesIn("failed")).isEqualTo(3);
    assertThat(count()).isZero();
  }

  @Test
  void aLongLetterIsStoredCutAtAParagraph() throws IOException {
    String p = "x".repeat(4000);
    Files.writeString(dir.resolve("2026-10-22-en.md"), "## H\n\n" + p + "\n\n" + p);

    assertThat(worker().scanOnce()).isEqualTo(1);

    assertThat(jdbc.queryForObject("SELECT content_text FROM poems", String.class)).isEqualTo("H\n\n" + p);
  }

  @Test
  void aSymlinkIsNotFollowed() throws IOException {
    Path target = Files.writeString(outer.resolve("outside.md"), "secret");
    Files.createSymbolicLink(dir.resolve("2026-10-22-vi.md"), target);

    assertThat(worker().scanOnce()).isZero();

    assertThat(count()).isZero();
    assertThat(Files.isSymbolicLink(dir.resolve("2026-10-22-vi.md"))).isTrue();
  }

  @Test
  void aHardLinkedFileIsRefused() throws IOException {
    Path original = Files.writeString(outer.resolve("original.md"), "secret");
    Files.createLink(dir.resolve("2026-10-22-vi.md"), original);

    assertThat(worker().scanOnce()).isZero();

    assertThat(count()).isZero();
    assertThat(filesIn("failed")).isEqualTo(1);
  }

  @Test
  void aSymlinkedOrNonDirectoryResultFolderStopsTheScan() throws IOException {
    Path elsewhere = Files.createDirectory(outer.resolve("elsewhere"));
    Files.createSymbolicLink(dir.resolve("skipped"), elsewhere);
    copyLetter("2026-10-22-vi.md");

    assertThat(worker().scanOnce()).isZero();
    assertThat(dir.resolve("2026-10-22-vi.md")).exists();

    Files.delete(dir.resolve("skipped"));
    Files.writeString(dir.resolve("failed"), "a file");
    assertThat(worker().scanOnce()).isZero();
    assertThat(count()).isZero();
  }

  @Test
  void aFileStillBeingWrittenIsLeftForTheNextScan() throws IOException {
    copyLetter("2026-10-22-vi.md");

    assertThat(worker(true, 3600, 200000).scanOnce()).isZero();

    assertThat(dir.resolve("2026-10-22-vi.md")).exists();
  }

  @Test
  void aMissingFolderIsNotAnError() {
    var props = new ThoughtProperties(true, dir.resolve("nope").toString(), 60, 0, 1000, new ThoughtProperties.Accounts("a", "b"));
    assertThat(new ShareThoughtWorker(props, new ThoughtRepository(jdbc), Clock.systemUTC()).scanOnce()).isZero();
  }

  @Test
  void aDatabaseErrorLeavesTheFileForTheNextScan() throws IOException {
    copyLetter("2026-10-22-vi.md");
    jdbc.execute("ALTER TABLE poems ADD CONSTRAINT chk_len CHECK (CHAR_LENGTH(content_text) < 5)");
    try {
      assertThat(worker().scanOnce()).isZero();
      assertThat(dir.resolve("2026-10-22-vi.md")).exists();
      assertThat(filesIn("failed")).isZero();
    } finally {
      jdbc.execute("ALTER TABLE poems DROP CONSTRAINT chk_len");
    }
  }

  @Test
  void aFailedListingIsLoggedNotThrown() throws IOException {
    Path file = Files.writeString(outer.resolve("afile"), "x");
    var props = new ThoughtProperties(true, file.toString(), 60, 0, 1000, new ThoughtProperties.Accounts("a", "b"));
    assertThat(new ShareThoughtWorker(props, new ThoughtRepository(jdbc), Clock.systemUTC()).scanOnce()).isZero();
  }

  @Test
  void theScheduledScanDoesNothingWhenDisabledAndScansWhenEnabled() throws IOException {
    copyLetter("2026-10-22-vi.md");

    worker(false, 0, 200000).scheduledScan();
    assertThat(count()).isZero();
    disabledWorker.scheduledScan();
    assertThat(count()).isZero();

    worker(true, 0, 200000).scheduledScan();
    assertThat(count()).isEqualTo(2);
    assertThat(LocalDate.of(2026, 10, 22)).isEqualTo(jdbc.queryForObject("SELECT DISTINCT local_date FROM poems", java.sql.Date.class).toLocalDate());
  }
}
