package com.contentcreator.news;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of {@code POST /api/v1/news-summaries}: the stories the coworker publishes. */
public record NewsPublishRequest(@NotEmpty @Size(max = 50) List<@Valid Item> items) {

  /**
   * One story.
   *
   * @param title the headline
   * @param summary the summary text (Vietnamese)
   * @param category optional, e.g. health, society, weather
   * @param source optional outlet name
   * @param url the article link (http or https); stories with a link already stored are skipped
   * @param published optional ISO date or date-time
   */
  public record Item(
      @NotBlank @Size(max = 300) String title,
      @NotBlank @Size(max = 5000) String summary,
      @Size(max = 60) String category,
      @Size(max = 120) String source,
      @NotBlank @Size(max = 1000) @Pattern(regexp = "(?i)^https?://.+") String url,
      @Size(max = 40) String published) {

    NewsItem toNewsItem() {
      return new NewsItem(title.trim(), summary.trim(), blankToNull(category), blankToNull(source), url.trim(), NewsJsonParser.date(published));
    }

    private static String blankToNull(String s) {
      return s == null || s.isBlank() ? null : s.trim();
    }
  }
}
