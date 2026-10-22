package com.contentcreator.thoughts;

import com.contentcreator.news.NewsFileWorker;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ShareThoughtWorker: watches a folder for daily letters named {@code yyyy-MM-dd-vi.md} / {@code yyyy-MM-dd-en.md}
 * and stores each as one Shared Thought (PUBLIC, PENDING moderation) in ElderEase's {@code poems} table, written by
 * the system account of the file's language. Done files move to {@code processed/}, unusable ones to
 * {@code failed/}, other {@code .md} names to {@code skipped/}. A file whose account does not exist yet, or one hit
 * by a database error, stays in place and is tried again on the next scan. Only regular files directly inside the
 * folder are read (no links, nothing larger than the limit, nothing still being written).
 */
@Component
public class ShareThoughtWorker {

  private static final Logger log = LoggerFactory.getLogger(ShareThoughtWorker.class);
  private static final Pattern NAME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-(vi|en)\\.md$");
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
  private static final List<String> FOLDERS = List.of("processed", "failed", "skipped");

  private final ThoughtProperties props;
  private final ThoughtRepository repository;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public ShareThoughtWorker(ThoughtProperties props, ThoughtRepository repository, Clock clock) {
    this.props = props;
    this.repository = repository;
    this.clock = clock;
  }

  /** Runs one scan every poll interval when the worker is enabled. */
  @Scheduled(fixedDelayString = "${contentcreator.thoughts.poll-seconds}", timeUnit = TimeUnit.SECONDS)
  public void scheduledScan() {
    if (props.enabled()) {
      scanOnce();
    }
  }

  /**
   * Reads every new letter now.
   *
   * @return the number of new thoughts stored
   */
  public int scanOnce() {
    if (!running.compareAndSet(false, true)) {
      return 0;
    }
    try {
      Path dir = Path.of(props.directory());
      if (!Files.isDirectory(dir)) {
        return 0;
      }
      for (String folder : FOLDERS) {
        if (!NewsFileWorker.folderIsSafe(dir.resolve(folder))) {
          log.error("thought_scan refused: {}/ is a link or not a directory", folder);
          return 0;
        }
      }
      List<Path> files;
      try (Stream<Path> list = Files.list(dir)) {
        files = list.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md")).sorted().toList();
      }
      Set<String> missingAccounts = new HashSet<>();
      int stored = 0;
      for (Path file : files) {
        stored += handle(dir, file, missingAccounts);
      }
      return stored;
    } catch (IOException | DataAccessException e) {
      log.error("thought_scan failed: {}", e.toString());
      return 0;
    } finally {
      running.set(false);
    }
  }

  private int handle(Path dir, Path file, Set<String> missingAccounts) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return 0;
    }
    if (clock.instant().isBefore(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().plusSeconds(props.settleSeconds()))) {
      return 0; // still being written, or just arrived: look again next time
    }
    String name = file.getFileName().toString();
    Matcher m = NAME.matcher(name);
    LocalDate day = m.matches() ? parseDay(m.group(1)) : null;
    if (day == null) {
      log.info("thought_skipped file={} reason=name is not yyyy-MM-dd-vi.md or -en.md", name);
      move(dir, file, "skipped");
      return 0;
    }
    String username = "vi".equals(m.group(2)) ? props.accounts().vi() : props.accounts().en();
    Optional<String> writer = repository.findWriterId(username);
    if (writer.isEmpty()) {
      if (missingAccounts.add(username)) {
        log.warn("thought_account_missing username={} (letters for it stay in the folder)", username);
      }
      return 0;
    }
    String text;
    try {
      text = LetterText.toPlainText(readBounded(file));
    } catch (IOException | IllegalArgumentException e) {
      log.error("thought_failed file={} reason={}", name, e.toString());
      move(dir, file, "failed");
      return 0;
    }
    if (text.isEmpty()) {
      log.error("thought_failed file={} reason=no usable text", name);
      move(dir, file, "failed");
      return 0;
    }
    try {
      boolean known = repository.exists(writer.get(), day);
      if (!known) {
        repository.insertPending(writer.get(), text, day, clock.instant());
      }
      move(dir, file, "processed");
      log.info("thought_done file={} stored={}", name, !known);
      return known ? 0 : 1;
    } catch (DataAccessException e) {
      log.error("thought_db_error file={} reason={} (left for the next scan)", name, e.toString());
      return 0;
    }
  }

  /** The date, or {@code null} when it is not a real calendar day. */
  private static LocalDate parseDay(String text) {
    try {
      return LocalDate.parse(text, DAY);
    } catch (java.time.DateTimeException e) {
      return null;
    }
  }

  /**
   * Reads the file through one no-follow handle and never more than the size limit: refuses a file with more than
   * one hard link, and stops after limit+1 bytes so a file that grows while being read cannot exceed the cap.
   */
  private String readBounded(Path file) throws IOException {
    try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      if (NewsFileWorker.linkCount(file) > 1) {
        throw new IllegalArgumentException("file has more than one hard link");
      }
      byte[] bytes = Channels.newInputStream(channel).readNBytes((int) Math.min(props.maxBytes(), Integer.MAX_VALUE - 8) + 1);
      if (bytes.length > props.maxBytes()) {
        throw new IllegalArgumentException("file larger than " + props.maxBytes() + " bytes");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private void move(Path dir, Path file, String folder) throws IOException {
    Path target = dir.resolve(folder);
    Files.createDirectories(target);
    String stamp = Duration.ofMillis(clock.millis()).toString().replaceAll("[^A-Za-z0-9]", "");
    Files.move(file, target.resolve(file.getFileName() + "." + stamp), StandardCopyOption.REPLACE_EXISTING);
  }
}
