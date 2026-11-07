package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.contentcreator.gemini.GeminiKeyResolver;
import com.contentcreator.news.NewsCategory;
import com.contentcreator.news.NewsWorkerProperties;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class NewsCollectionWorkerTest {

  static final String KEY = "fake-key-not-real-0123456789";
  static final CollectionSettings SETTINGS = new CollectionSettings("T", 3, 300);
  static final List<PromptBuilder.Site> SITES = List.of(new PromptBuilder.Site("Example", "example.test"));
  static final List<NewsCategory> SIX = List.of("ECONOMY", "HEALTH", "POLITICS", "WORLD", "STOCKS", "DOMESTIC").stream()
      .map(c -> new NewsCategory(c, "x", c, c)).toList();
  static final CountryConfig US = new CountryConfig("US", "United States", "en", null, SIX, SITES);
  static final CountryCategoryCollector.Outcome OK = new CountryCategoryCollector.Outcome(false, false, false, 1, 1, 0);

  CollectionConfigRepository config = mock(CollectionConfigRepository.class);
  CountryCategoryCollector collector = mock(CountryCategoryCollector.class);
  GeminiKeyResolver keys = mock(GeminiKeyResolver.class);
  ListAppender<ILoggingEvent> logs = new ListAppender<>();
  Logger logger = (Logger) LoggerFactory.getLogger(NewsCollectionWorker.class);

  @BeforeEach
  void setUp() {
    when(config.settings()).thenReturn(Optional.of(SETTINGS));
    when(keys.resolve()).thenReturn(KEY);
    when(collector.collect(any(), any(), any(), anyString())).thenReturn(OK);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  NewsCollectionWorker worker(boolean enabled, boolean runOnStartup) {
    return new NewsCollectionWorker(config, collector, keys, new NewsWorkerProperties(enabled, "c", "UTC", runOnStartup, 10));
  }

  String allLogs() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "|" + b);
  }

  @Test
  void runOnce_anEnabledUsCountryWithSixCategoriesMakesSixCalls_andADisabledCountryNone() {
    // the config repository already leaves disabled countries out, so only US is returned
    when(config.enabledCountries()).thenReturn(List.of(US));

    int calls = worker(true, false).runOnce();

    assertThat(calls).isEqualTo(6);
    verify(collector, times(6)).collect(eq(US), any(NewsCategory.class), eq(SETTINGS), eq(KEY));
    verify(keys, times(1)).resolve();
  }

  @Test
  void runOnce_noEnabledCountryMakesNoCall() {
    when(config.enabledCountries()).thenReturn(List.of());

    assertThat(worker(true, false).runOnce()).isZero();
    verify(collector, never()).collect(any(), any(), any(), any());
  }

  @Test
  void runOnce_oneFailingCallDoesNotStopTheLoop() {
    when(config.enabledCountries()).thenReturn(List.of(US));
    when(collector.collect(any(), eq(SIX.get(1)), any(), anyString())).thenThrow(new IllegalStateException("boom " + KEY));

    int calls = worker(true, false).runOnce();

    assertThat(calls).isEqualTo(6);
    verify(collector, times(6)).collect(any(), any(), any(), anyString());
    assertThat(allLogs()).contains("news_collect_call_failed country=US category=HEALTH type=IllegalStateException")
        .doesNotContain(KEY).doesNotContain("boom");
  }

  @Test
  void runOnce_stopsForTheDayWhenTheBudgetIsReached() {
    CountryConfig gb = new CountryConfig("GB", "United Kingdom", "en", null, SIX, SITES);
    when(config.enabledCountries()).thenReturn(List.of(US, gb));
    CountryCategoryCollector.Outcome budget = new CountryCategoryCollector.Outcome(true, false, false, 0, 0, 0);
    when(collector.collect(any(), any(), any(), anyString())).thenReturn(OK, OK, OK, OK, OK, OK, OK, OK, budget);

    int calls = worker(true, false).runOnce();

    assertThat(calls).isEqualTo(8);
    assertThat(allLogs()).contains("daily budget reached after 8 calls");
    verify(collector, times(9)).collect(any(), any(), any(), anyString());
  }

  @Test
  void runOnce_aMissingKeyStopsTheRunAfterTheFirstRecordedCall() {
    when(config.enabledCountries()).thenReturn(List.of(US));
    when(keys.resolve()).thenReturn("");
    when(collector.collect(any(), any(), any(), eq(""))).thenReturn(new CountryCategoryCollector.Outcome(false, true, true, 0, 0, 0));

    int calls = worker(true, false).runOnce();

    assertThat(calls).isEqualTo(1);
    verify(collector, times(1)).collect(any(), any(), any(), eq(""));
    assertThat(allLogs()).contains("no Gemini key");
  }

  @Test
  void runOnce_aCountryWithoutSitesIsSkipped() {
    CountryConfig noSites = new CountryConfig("FR", "France", "fr", null, SIX, List.of());
    when(config.enabledCountries()).thenReturn(List.of(noSites));

    assertThat(worker(true, false).runOnce()).isZero();
    verify(collector, never()).collect(any(), any(), any(), any());
    assertThat(allLogs()).contains("news_collect_skipped country=FR reason=no_sites");
  }

  @Test
  void runOnce_withoutASettingsRowIsSkipped() {
    when(config.settings()).thenReturn(Optional.empty());

    assertThat(worker(true, false).runOnce()).isEqualTo(-1);
    verify(config, never()).enabledCountries();
    assertThat(allLogs()).contains("no settings row");
  }

  @Test
  void runOnce_aConfigurationReadFailureEndsTheRunWithoutThrowing_andTheNextRunWorks() {
    when(config.enabledCountries()).thenThrow(new IllegalStateException("db down")).thenReturn(List.of());
    NewsCollectionWorker worker = worker(true, false);

    assertThat(worker.runOnce()).isEqualTo(-1);
    assertThat(allLogs()).contains("news_collect_run failed type=IllegalStateException");
    assertThat(worker.runOnce()).isZero();
  }

  @Test
  void runOnce_aSecondRunWhileOneIsGoingIsSkipped() throws Exception {
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(config.enabledCountries()).thenReturn(List.of(US));
    when(collector.collect(any(), any(), any(), anyString())).thenAnswer(inv -> {
      inside.countDown();
      release.await(5, TimeUnit.SECONDS);
      return OK;
    });
    NewsCollectionWorker worker = worker(true, false);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> first = pool.submit(worker::runOnce);
      assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

      assertThat(worker.runOnce()).isEqualTo(-1);
      assertThat(allLogs()).contains("previous run still going");

      release.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(6);
      assertThat(worker.runOnce()).isEqualTo(6);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void scheduledRun_runsWhenEnabledAndDoesNothingWhenDisabled() {
    when(config.enabledCountries()).thenReturn(List.of(US));

    worker(false, false).scheduledRun();
    verify(config, never()).settings();

    worker(true, false).scheduledRun();
    verify(collector, times(6)).collect(any(), any(), any(), anyString());
  }

  @Test
  void runAtStartup_runsOnlyWhenEnabledAndRunOnStartupIsOn() {
    when(config.enabledCountries()).thenReturn(List.of(US));

    worker(true, false).runAtStartup();
    worker(false, true).runAtStartup();
    verify(config, never()).settings();

    worker(true, true).runAtStartup();
    verify(config, times(1)).settings();
  }
}
