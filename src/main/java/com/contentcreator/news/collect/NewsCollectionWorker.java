package com.contentcreator.news.collect;

import com.contentcreator.gemini.GeminiKeyResolver;
import com.contentcreator.news.NewsCategory;
import com.contentcreator.news.NewsWorkerProperties;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The hourly AI news collection: for every country the admin switched on and every category it selected, one Gemini call
 * through {@link CountryCategoryCollector}. Drafts land unpublished for an admin to review. Settings and countries are
 * re-read at the start of every run (nothing cached), and the API key is resolved once per run. Only one run at a time:
 * a tick that finds the previous run still going is skipped. One failing call never stops the loop; the run ends early
 * only when the daily budget is used up or the key is missing (that is recorded once, not per call).
 * Active when {@code contentcreator.news.worker.provider} is {@code gemini} (the default) and the worker is enabled.
 */
@Component
@ConditionalOnProperty(name = "contentcreator.news.worker.provider", havingValue = "gemini", matchIfMissing = true)
public class NewsCollectionWorker {

  private static final Logger log = LoggerFactory.getLogger(NewsCollectionWorker.class);

  private final CollectionConfigRepository config;
  private final CountryCategoryCollector collector;
  private final GeminiKeyResolver keys;
  private final NewsWorkerProperties props;
  private final AtomicBoolean running = new AtomicBoolean();

  /**
   * @param config reads the settings and enabled countries
   * @param collector runs one country and category
   * @param keys resolves the Gemini key
   * @param props the shared worker switches ({@code enabled}, {@code runOnStartup})
   */
  public NewsCollectionWorker(CollectionConfigRepository config, CountryCategoryCollector collector, GeminiKeyResolver keys, NewsWorkerProperties props) {
    this.config = config;
    this.collector = collector;
    this.keys = keys;
    this.props = props;
  }

  /** Hourly tick (UTC); does nothing when the worker is disabled. The schedule can be changed with {@code contentcreator.news.collect.cron}. */
  @Scheduled(cron = "${contentcreator.news.collect.cron:0 0 * * * *}", zone = "${contentcreator.news.collect.zone:UTC}")
  public void scheduledRun() {
    if (props.enabled()) {
      runOnce();
    }
  }

  /** Collects once right after start-up when the worker is enabled and {@code run-on-startup} is on. */
  @EventListener(ApplicationReadyEvent.class)
  void runAtStartup() {
    if (props.enabled() && props.runOnStartup()) {
      runOnce();
    }
  }

  /**
   * Runs one collection over all enabled countries and their categories.
   *
   * @return the number of Gemini calls attempted, or -1 when the run was skipped (another run going, no settings) or the configuration could not be read
   */
  public int runOnce() {
    if (!running.compareAndSet(false, true)) {
      log.info("news_collect_run skipped: previous run still going");
      return -1;
    }
    try {
      return collectAll();
    } catch (RuntimeException e) {
      log.error("news_collect_run failed type={}", e.getClass().getSimpleName());
      return -1;
    } finally {
      running.set(false);
    }
  }

  /**
   * The loop itself: settings, countries, one key resolution, then every country and category in order.
   *
   * @return the calls attempted, or -1 when there are no settings
   */
  private int collectAll() {
    Optional<CollectionSettings> settings = config.settings();
    if (settings.isEmpty()) {
      log.warn("news_collect_run skipped: no settings row");
      return -1;
    }
    List<CountryConfig> countries = config.enabledCountries();
    String key = keys.resolve();
    int calls = 0;
    for (CountryConfig country : countries) {
      if (country.sites().isEmpty()) {
        log.info("news_collect_skipped country={} reason=no_sites", country.code());
        continue;
      }
      for (NewsCategory category : country.categories()) {
        CountryCategoryCollector.Outcome outcome;
        try {
          outcome = collector.collect(country, category, settings.get(), key);
        } catch (RuntimeException e) {
          log.error("news_collect_call_failed country={} category={} type={}", country.code(), category.code(), e.getClass().getSimpleName());
          calls++;
          continue;
        }
        if (outcome.budgetReached()) {
          log.info("news_collect_run stopped: daily budget reached after {} calls", calls);
          return calls;
        }
        calls++;
        if (outcome.keyMissing()) {
          log.warn("news_collect_run stopped: no Gemini key (recorded once as KEY_MISSING)");
          return calls;
        }
      }
    }
    log.info("news_collect_run done countries={} calls={}", countries.size(), calls);
    return calls;
  }
}
