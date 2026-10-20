package com.contentcreator.news;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The NewsSummaryWorker: takes the summaries the Claude coworker publishes through the API and inserts the
 * new ones into ElderEase's news_summary table as unpublished drafts. Nothing reaches elders until an admin
 * reviews and publishes a draft.
 */
@Component
public class NewsSummaryWorker {

  private static final Logger log = LoggerFactory.getLogger(NewsSummaryWorker.class);

  private final NewsDraftRepository repository;
  private final Clock clock;

  public NewsSummaryWorker(NewsDraftRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Inserts the drafts not stored yet (same headline and day). Returns how many were new. */
  public int publish(List<NewsDraft> drafts) {
    int stored = 0;
    for (NewsDraft draft : drafts) {
      if (!repository.exists(draft)) {
        repository.insert(draft, clock.instant());
        stored++;
      }
    }
    log.info("news_summary_drafts received={} stored={}", drafts.size(), stored);
    return stored;
  }
}
