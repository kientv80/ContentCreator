package com.contentcreator.news.collect;

import java.time.Instant;

/** Tells whether a story was already collected for a country (backed by {@code news_story_source}). */
public interface SeenChecker {

  /**
   * @param country the two-letter country code
   * @param urlHash the SHA-256 hex hash of the normalized URL
   * @return true when a row for this country and hash already exists
   */
  boolean seenUrl(String country, String urlHash);

  /**
   * @param country the two-letter country code
   * @param titleHash the SHA-256 hex hash of the normalized title
   * @param since only rows created at or after this instant count
   * @return true when a row for this country and title hash exists in that window
   */
  boolean seenTitle(String country, String titleHash, Instant since);
}
