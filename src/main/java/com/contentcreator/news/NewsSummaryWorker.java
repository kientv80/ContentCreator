package com.contentcreator.news;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The NewsSummaryWorker: takes the news summaries the Claude coworker publishes through the API and inserts
 * the new ones into the news_summary table, and keeps the table tidy by removing old rows every night.
 */
@Component
public class NewsSummaryWorker {

  private static final Logger log = LoggerFactory.getLogger(NewsSummaryWorker.class);

  private final NewsSummaryService service;
  private final int retentionDays;

  public NewsSummaryWorker(NewsSummaryService service, @Value("${contentcreator.news.retention-days:30}") int retentionDays) {
    this.service = service;
    this.retentionDays = retentionDays;
  }

  /** Inserts the stories whose link is not stored yet. Returns how many were new. */
  public int publish(List<NewsItem> items) {
    int stored = service.store(items);
    log.info("news_summary_publish received={} stored={}", items.size(), stored);
    return stored;
  }

  /** Nightly clean-up (Vietnam time): removes stories collected more than {@code retention-days} ago. */
  @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Ho_Chi_Minh")
  public void purgeOld() {
    int purged = service.purgeOlderThan(retentionDays);
    log.info("news_summary_purge purged={}", purged);
  }
}
