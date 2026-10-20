package com.contentcreator.news;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Body of {@code POST /api/v1/news-summaries}. The limits are those of the news_summary table and the admin form. */
public record NewsPublishRequest(@NotEmpty @Size(max = 50) List<@Valid Item> items) {

  static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  /**
   * One summary.
   *
   * @param headline the Vietnamese headline, at most 120 characters
   * @param summary the Vietnamese summary, at most 1,000 characters (put the outlet at the end, e.g. "(Nguồn: VnExpress)")
   * @param newsDate optional ISO date or date-time; the Vietnam day it belongs to; today when absent
   */
  public record Item(@NotBlank @Size(max = 120) String headline, @NotBlank @Size(max = 1000) String summary, @Size(max = 40) String newsDate) {

    NewsDraft toDraft(LocalDate today) {
      return new NewsDraft(headline.trim(), summary.trim(), day(today));
    }

    private LocalDate day(LocalDate today) {
      if (newsDate == null || newsDate.isBlank()) {
        return today;
      }
      try {
        return Instant.parse(newsDate.trim()).atZone(VIETNAM).toLocalDate();
      } catch (DateTimeParseException ignored) {
        // maybe a plain date
      }
      try {
        return LocalDate.parse(newsDate.trim());
      } catch (DateTimeParseException e) {
        return today;
      }
    }
  }
}
