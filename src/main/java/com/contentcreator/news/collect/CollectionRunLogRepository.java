package com.contentcreator.news.collect;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The run log ({@code news_collection_run}, ElderEase V62): one INSERT per attempted Gemini call and a count of rows
 * since a given instant. The daily budget is that count, so it survives restarts. It never updates or deletes.
 */
@Repository
public class CollectionRunLogRepository {

  /** The {@code news_collection_run.error} column limit. */
  static final int MAX_ERROR = 120;

  /**
   * One attempted call.
   *
   * @param startedAt when the call began
   * @param country the two-letter country code
   * @param category the category code
   * @param stories stories in the answer (kept plus rejected)
   * @param stored stories stored in drafts
   * @param rejected stories dropped by the filter or as duplicates
   * @param failed whether the call failed
   * @param error a short status such as {@code RESOURCE_EXHAUSTED} or {@code KEY_MISSING}, null when none; never a message body or key
   * @param durationMs how long the call took
   */
  public record Run(Instant startedAt, String country, String category, int stories, int stored, int rejected, boolean failed, String error, int durationMs) {}

  private final JdbcTemplate jdbc;

  /** @param jdbc the shared JDBC template */
  public CollectionRunLogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts one run row; an error text longer than 120 characters is cut.
   *
   * @param run the attempted call
   */
  public void insert(Run run) {
    String error = run.error() == null || run.error().length() <= MAX_ERROR ? run.error() : run.error().substring(0, MAX_ERROR);
    jdbc.update(
        "INSERT INTO news_collection_run (started_at, country_code, category_code, stories, stored_count, rejected, failed, error, duration_ms) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        Timestamp.from(run.startedAt()), run.country(), run.category(), run.stories(), run.stored(), run.rejected(), run.failed(), error,
        run.durationMs());
  }

  /**
   * Counts the calls recorded at or after an instant (the start of the Vietnam day).
   *
   * @param since the inclusive start
   * @return the number of rows with {@code started_at >= since}
   */
  public int countCallsSince(Instant since) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM news_collection_run WHERE started_at >= ?", Integer.class, Timestamp.from(since));
    return count == null ? 0 : count;
  }
}
