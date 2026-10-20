package com.contentcreator.news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The NewsFileWorker: watches a folder for new daily bulletin files ({@code *.json}), builds news summaries from
 * each one and inserts them into ElderEase's news_summary table as unpublished drafts for an admin to review.
 * A finished file moves to {@code processed/}; one that cannot be read moves to {@code failed/}. Only regular files
 * directly inside the folder are read (no links, nothing larger than the limit, nothing still being written).
 */
@Component
public class NewsFileWorker {

  private static final Logger log = LoggerFactory.getLogger(NewsFileWorker.class);
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private final NewsFileProperties props;
  private final NewsDraftRepository repository;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public NewsFileWorker(NewsFileProperties props, NewsDraftRepository repository, Clock clock) {
    this.props = props;
    this.repository = repository;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${contentcreator.news.file-worker.poll-seconds}", timeUnit = java.util.concurrent.TimeUnit.SECONDS)
  public void scheduledScan() {
    if (props.enabled()) {
      scanOnce();
    }
  }

  /** Reads every new file now. Returns the number of new drafts stored. */
  public int scanOnce() {
    if (!running.compareAndSet(false, true)) {
      return 0;
    }
    try {
      Path dir = Path.of(props.directory());
      if (!Files.isDirectory(dir)) {
        return 0;
      }
      List<Path> files;
      try (Stream<Path> list = Files.list(dir)) {
        files = list.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json")).sorted().toList();
      }
      int stored = 0;
      for (Path file : files) {
        stored += handle(dir, file);
      }
      return stored;
    } catch (IOException e) {
      log.error("news_file_scan failed: {}", e.toString());
      return 0;
    } finally {
      running.set(false);
    }
  }

  private int handle(Path dir, Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      return 0;
    }
    if (clock.instant().isBefore(Files.getLastModifiedTime(file).toInstant().plusSeconds(props.settleSeconds()))) {
      return 0; // still being written, or just arrived: look again next time
    }
    try {
      if (Files.size(file) > props.maxBytes()) {
        throw new IllegalArgumentException("file larger than " + props.maxBytes() + " bytes");
      }
      List<NewsDraft> drafts = NewsFileParser.parse(Files.readString(file, StandardCharsets.UTF_8), LocalDate.now(clock.withZone(VIETNAM)));
      int stored = 0;
      for (NewsDraft draft : drafts) {
        if (!repository.exists(draft)) {
          repository.insert(draft, clock.instant());
          stored++;
        }
      }
      move(dir, file, "processed");
      log.info("news_file_done file={} drafts={} stored={}", file.getFileName(), drafts.size(), stored);
      return stored;
    } catch (IOException | RuntimeException e) {
      log.error("news_file_failed file={} reason={}", file.getFileName(), e.toString());
      move(dir, file, "failed");
      return 0;
    }
  }

  private void move(Path dir, Path file, String folder) throws IOException {
    Path target = dir.resolve(folder);
    Files.createDirectories(target);
    String stamp = Duration.ofMillis(clock.millis()).toString().replaceAll("[^A-Za-z0-9]", "");
    Files.move(file, target.resolve(file.getFileName() + "." + stamp), StandardCopyOption.REPLACE_EXISTING);
  }
}
