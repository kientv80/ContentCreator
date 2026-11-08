package com.contentcreator.news.collect;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link SeenChecker} over {@code news_story_source} (ElderEase V62 and V64; SELECT only). */
@Repository
public class JdbcUrlSeenChecker implements SeenChecker {

  private final JdbcTemplate jdbc;

  /** @param jdbc the shared JDBC template */
  public JdbcUrlSeenChecker(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Looks the hash up by the unique key {@code (country_code, url_hash)}.
   *
   * @param country the two-letter country code
   * @param urlHash the SHA-256 hex hash of the normalized URL
   * @return true when a row exists
   */
  @Override
  public boolean seenUrl(String country, String urlHash) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM news_story_source WHERE country_code = ? AND url_hash = ?", Integer.class, country, urlHash);
    return count != null && count > 0;
  }

  /**
   * Looks the title hash up by the index {@code (country_code, title_hash, created_at)}.
   *
   * @param country the two-letter country code
   * @param titleHash the SHA-256 hex hash of the normalized title
   * @param since only rows created at or after this instant count
   * @return true when such a row exists
   */
  @Override
  public boolean seenTitle(String country, String titleHash, Instant since) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM news_story_source WHERE country_code = ? AND title_hash = ? AND created_at >= ?",
        Integer.class, country, titleHash, Timestamp.from(since));
    return count != null && count > 0;
  }
}
