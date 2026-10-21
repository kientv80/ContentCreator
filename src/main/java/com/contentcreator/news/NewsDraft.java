package com.contentcreator.news;

import java.time.LocalDate;

/**
 * One AI-collected summary on its way into the ElderEase news_summary table, as an unpublished draft.
 *
 * @param category one of DOMESTIC, WORLD, STOCKS, SCIENCE_TECH, OTHER (the news_summary.category codes)
 */
public record NewsDraft(String headline, String summary, LocalDate newsDate, String category) {

  /** The category code used when nothing better is known. */
  public static final String OTHER = "OTHER";
}
