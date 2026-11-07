package com.contentcreator.news.collect;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link UrlSeenChecker} over {@code news_story_source} (ElderEase V62; SELECT only). */
@Repository
public class JdbcUrlSeenChecker implements UrlSeenChecker {

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
  public boolean seen(String country, String urlHash) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM news_story_source WHERE country_code = ? AND url_hash = ?", Integer.class, country, urlHash);
    return count != null && count > 0;
  }
}
