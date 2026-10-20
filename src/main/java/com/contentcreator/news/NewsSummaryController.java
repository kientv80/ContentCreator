package com.contentcreator.news;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What the News Summary screen loads. */
@RestController
@RequestMapping("/api/v1/news-summaries")
public class NewsSummaryController {

  public record NewsSummaryDto(
      long id, String title, String summary, String category, String sourceName, String sourceUrl,
      String language, Instant publishedAt, Instant collectedAt) {}

  private final NewsSummaryService service;

  public NewsSummaryController(NewsSummaryService service) {
    this.service = service;
  }

  /** Newest first; {@code size} is capped at 50. */
  @GetMapping
  public List<NewsSummaryDto> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return service.latest(Math.max(page, 0), Math.min(Math.max(size, 1), 50)).stream()
        .map(n -> new NewsSummaryDto(n.getId(), n.getTitle(), n.getSummary(), n.getCategory(), n.getSourceName(),
            n.getSourceUrl(), n.getLanguage(), n.getPublishedAt(), n.getCollectedAt()))
        .toList();
  }
}
