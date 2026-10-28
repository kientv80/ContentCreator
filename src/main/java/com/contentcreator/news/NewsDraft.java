package com.contentcreator.news;

import java.time.LocalDate;
import java.util.List;

/**
 * One AI-collected summary on its way into the ElderEase news_summary table, as an unpublished draft.
 *
 * @param category one of DOMESTIC, WORLD, STOCKS, SCIENCE_TECH, OTHER (the news_summary.category codes)
 * @param extras key points and analysis of the stories that have any, in story order
 */
public record NewsDraft(String headline, String summary, LocalDate newsDate, String category, List<StoryExtra> extras) {

  public NewsDraft {
    extras = extras == null ? List.of() : List.copyOf(extras);
  }

  /** A draft without story extras. */
  public NewsDraft(String headline, String summary, LocalDate newsDate, String category) {
    this(headline, summary, newsDate, category, List.of());
  }

  /** The category code used when nothing better is known. */
  public static final String OTHER = "OTHER";
}
