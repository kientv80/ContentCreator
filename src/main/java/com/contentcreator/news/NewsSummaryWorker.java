package com.contentcreator.news;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The NewsSummaryWorker: on a schedule it asks Claude (Anthropic API, web search) for today's Vietnamese news and
 * inserts the new summaries into ElderEase's news_summary table as unpublished drafts. Nothing reaches elders
 * until an admin reviews and publishes a draft. One run at a time; a failed run is logged and the next one retries.
 */
@Component
public class NewsSummaryWorker {

  private static final Logger log = LoggerFactory.getLogger(NewsSummaryWorker.class);

  private final NewsCollector collector;
  private final NewsDraftRepository repository;
  private final NewsWorkerProperties props;
  private final Clock clock;
  private final AtomicBoolean running = new AtomicBoolean();

  public NewsSummaryWorker(NewsCollector collector, NewsDraftRepository repository, NewsWorkerProperties props, Clock clock) {
    this.collector = collector;
    this.repository = repository;
    this.props = props;
    this.clock = clock;
  }

  @Scheduled(cron = "${contentcreator.news.worker.cron}", zone = "${contentcreator.news.worker.zone}")
  public void scheduledRun() {
    if (props.enabled()) {
      runOnce();
    }
  }

  @EventListener(ApplicationReadyEvent.class)
  void runAtStartup() {
    if (props.enabled() && props.runOnStartup()) {
      runOnce();
    }
  }

  /** Runs one collection. Returns the number of new drafts stored, or -1 when it was skipped or failed. */
  public int runOnce() {
    if (!running.compareAndSet(false, true)) {
      log.info("news_summary_run skipped: previous run still going");
      return -1;
    }
    try {
      List<NewsDraft> drafts = collector.collect(props.maxItems());
      int stored = 0;
      for (NewsDraft draft : drafts) {
        if (!repository.exists(draft)) {
          repository.insert(draft, clock.instant());
          stored++;
        }
      }
      log.info("news_summary_run collected={} stored={}", drafts.size(), stored);
      return stored;
    } catch (Exception e) {
      log.error("news_summary_run failed: {}", e.toString());
      return -1;
    } finally {
      running.set(false);
    }
  }
}
