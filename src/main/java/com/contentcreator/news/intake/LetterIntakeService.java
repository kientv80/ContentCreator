package com.contentcreator.news.intake;

import com.contentcreator.thoughts.LetterStore;
import com.contentcreator.thoughts.LetterText;
import com.contentcreator.thoughts.ThoughtProperties;
import com.contentcreator.thoughts.ThoughtRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/** Stores a letter posted to the intake as pending Shared Thoughts, like the folder worker does (FR-572). */
@Service
@ConditionalOnExpression("!'${contentcreator.news.intake.token-sha256:}'.isBlank() || !'${contentcreator.news.intake.allowed-cidrs:}'.isBlank()")
public class LetterIntakeService {

  /** Outcome of one letter call. */
  public enum Status { CREATED, ALREADY_STORED, NO_VALID_POST, INVALID_LANGUAGE, INVALID_BODY, WRITER_MISSING }

  /**
   * The result of one letter call.
   *
   * @param status what happened
   * @param received posts found in the letter
   * @param stored posts saved
   * @param skipped posts not saved (already stored)
   * @param duplicates posts skipped because they were stored before
   */
  public record Result(Status status, int received, int stored, int skipped, int duplicates) {}

  private static final Logger log = LoggerFactory.getLogger(LetterIntakeService.class);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  private final ThoughtProperties props;
  private final ThoughtRepository repository;
  private final LetterStore store;
  private final Clock clock;

  /**
   * @param props supplies the writer account of each language
   * @param repository finds the writer
   * @param clock the time source (default date and creation time)
   */
  public LetterIntakeService(ThoughtProperties props, ThoughtRepository repository, Clock clock) {
    this.props = props;
    this.repository = repository;
    this.store = new LetterStore(repository);
    this.clock = clock;
  }

  /**
   * Processes a body already known to be a letter.
   *
   * @param root the JSON object with {@code letter}, optional {@code language} (default vi) and {@code date}
   * @return the outcome; never throws for bad input
   */
  public Result process(JsonNode root) {
    String language = root.path("language").isTextual() ? root.path("language").asText() : root.has("language") ? "" : "vi";
    if (!"vi".equals(language) && !"en".equals(language)) {
      return fail(Status.INVALID_LANGUAGE);
    }
    LocalDate day = parseDay(root.path("date"), root.has("date"));
    if (day == null) {
      return fail(Status.INVALID_BODY);
    }
    String username = "vi".equals(language) ? props.accounts().vi() : props.accounts().en();
    Optional<String> writer = repository.findWriterId(username);
    if (writer.isEmpty()) {
      log.warn("letter_intake_rejected reason=writer_account_missing language={}", language);
      return fail(Status.WRITER_MISSING);
    }
    List<String> posts = LetterText.splitPosts(root.path("letter").asText());
    if (posts.isEmpty()) {
      return fail(Status.NO_VALID_POST);
    }
    log.info("letter_intake_received language={} posts={}", language, posts.size());
    int stored = store.store(writer.get(), day, posts, clock.instant());
    log.info("letter_intake_stored language={} posts={} stored={}", language, posts.size(), stored);
    int skipped = posts.size() - stored;
    return new Result(stored == 0 ? Status.ALREADY_STORED : Status.CREATED, posts.size(), stored, skipped, skipped);
  }

  /** The strict yyyy-MM-dd date, today when absent, {@code null} when present but not a real day. */
  private LocalDate parseDay(JsonNode node, boolean present) {
    if (!present || node.isNull()) {
      return LocalDate.now(clock);
    }
    if (!node.isTextual()) {
      return null;
    }
    try {
      return LocalDate.parse(node.asText(), DAY);
    } catch (java.time.DateTimeException e) {
      return null;
    }
  }

  private static Result fail(Status status) {
    log.info("letter_intake_rejected reason={}", status.name().toLowerCase());
    return new Result(status, 0, 0, 0, 0);
  }
}
