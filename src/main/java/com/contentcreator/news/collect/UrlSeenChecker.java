package com.contentcreator.news.collect;

/** Tells whether an article URL was already collected for a country (backed by {@code news_story_source}). */
public interface UrlSeenChecker {

  /**
   * @param country the two-letter country code
   * @param urlHash the SHA-256 hex hash of the normalized URL
   * @return true when a row for this country and hash already exists
   */
  boolean seen(String country, String urlHash);
}
