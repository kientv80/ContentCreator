package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.contentcreator.gemini.GeminiClient;
import com.contentcreator.gemini.GeminiException;
import com.contentcreator.gemini.GeminiResult;
import com.contentcreator.news.NewsCategory;
import com.contentcreator.news.NewsDraft;
import com.contentcreator.news.NewsDraftRepository;
import com.contentcreator.news.StoryUrl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CountryCategoryCollectorTest {

  static final Instant NOW = Instant.parse("2026-11-07T05:00:00Z"); // 12:00 Vietnam time
  static final String KEY = "fake-gemini-key-do-not-log-0123456789";
  static final NewsCategory HEALTH = new NewsCategory("HEALTH", "h", "Sức khỏe", "Health");
  static final CountryConfig US = new CountryConfig("US", "United States", "en", "Local news", List.of(HEALTH),
      List.of(new PromptBuilder.Site("Example News", "example.test")));
  static final CollectionSettings SETTINGS = new CollectionSettings("Today {date}: {category} in {country}, {language}, {sites}, max {max}.", 3, 300);

  @Autowired JdbcTemplate jdbc;
  GeminiClient gemini = mock(GeminiClient.class);
  CollectionRunLogRepository runLog;
  NewsDraftRepository drafts;
  CountryCategoryCollector collector;
  ListAppender<ILoggingEvent> logs = new ListAppender<>();
  Logger logger = (Logger) LoggerFactory.getLogger(CountryCategoryCollector.class);

  @BeforeEach
  void setUp() {
    clean();
    runLog = new CollectionRunLogRepository(jdbc);
    drafts = new NewsDraftRepository(jdbc);
    collector = collector(drafts, runLog);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void clean() {
    logger.detachAppender(logs);
    jdbc.update("DELETE FROM news_story_source");
    jdbc.update("DELETE FROM news_story_extra");
    jdbc.update("DELETE FROM news_summary");
    jdbc.update("DELETE FROM news_collection_run");
  }

  CountryCategoryCollector collector(NewsDraftRepository d, CollectionRunLogRepository r) {
    return new CountryCategoryCollector(gemini, r, d, new JdbcUrlSeenChecker(jdbc), new CollectProperties(true),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  static String story(int n) {
    return "{\"title\":\"Title " + n + "\",\"summary\":\"Summary " + n + ".\",\"source\":\"Example News\",\"url\":\"https://example.test/a" + n + "\"}";
  }

  static String answer(int... stories) {
    StringBuilder sb = new StringBuilder("{\"sections\":{\"health\":[");
    for (int i = 0; i < stories.length; i++) sb.append(i == 0 ? "" : ",").append(story(stories[i]));
    return sb.append("]}}").toString();
  }

  void gemini(String text) {
    when(gemini.generate(anyString(), eq(KEY))).thenReturn(new GeminiResult(text, List.of("example.test"), false));
  }

  String allLogs() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
  }

  @Test
  void collect_storesAnUnpublishedDraftWithCountryLanguageAndSourceUrls_andRecordsTheRun() {
    gemini(answer(1, 2));

    var outcome = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome).isEqualTo(new CountryCategoryCollector.Outcome(false, false, false, 2, 2, 0));
    Map<String, Object> summary = jdbc.queryForMap("SELECT * FROM news_summary");
    assertThat(summary.get("COUNTRY_CODE")).isEqualTo("US");
    assertThat(summary.get("LANGUAGE")).isEqualTo("en");
    assertThat(summary.get("CATEGORY")).isEqualTo("HEALTH");
    assertThat(summary.get("PUBLISHED")).isEqualTo(false);
    assertThat((String) summary.get("HEADLINE")).isEqualTo("Health · 12:00 07/11");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source WHERE country_code = 'US'", Integer.class)).isEqualTo(2);
    Map<String, Object> run = jdbc.queryForMap("SELECT * FROM news_collection_run");
    assertThat(run.get("COUNTRY_CODE")).isEqualTo("US");
    assertThat(run.get("CATEGORY_CODE")).isEqualTo("HEALTH");
    assertThat(run.get("STORIES")).isEqualTo(2);
    assertThat(run.get("STORED")).isEqualTo(2);
    assertThat(run.get("REJECTED")).isEqualTo(0);
    assertThat(run.get("FAILED")).isEqualTo(false);
    assertThat(run.get("ERROR")).isNull();
    assertThat(allLogs()).contains("news_collect_call country=US category=HEALTH stories=2 stored=2 ms=");
  }

  @Test
  void collect_buildsThePromptFromTheTemplateAndPassesTheResolvedKey() {
    gemini(answer(1));

    collector.collect(US, HEALTH, SETTINGS, KEY);

    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(gemini).generate(prompt.capture(), eq(KEY));
    assertThat(prompt.getValue())
        .startsWith("Today 2026-11-07: Health in United States, English, Example News (example.test), max 3.")
        .contains(PromptBuilder.NOTES_HEADING + "\nLocal news");
  }

  @Test
  void collect_usesTheVietnameseCategoryNameAndLanguageNameForANonEnglishCountry() {
    CountryConfig vn = new CountryConfig("VN", "Vietnam", "vi", null, List.of(HEALTH), US.sites());
    gemini(answer(1));

    collector.collect(vn, HEALTH, SETTINGS, KEY);

    ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
    verify(gemini).generate(prompt.capture(), eq(KEY));
    assertThat(prompt.getValue()).startsWith("Today 2026-11-07: Sức khỏe in Vietnam, Vietnamese,");
  }

  @Test
  void collect_aSecondRunWithTheSameAnswerStoresNothing() {
    gemini(answer(1, 2));
    collector.collect(US, HEALTH, SETTINGS, KEY);

    var second = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(second).isEqualTo(new CountryCategoryCollector.Outcome(false, false, false, 2, 0, 2));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_collection_run", Integer.class)).isEqualTo(2);
  }

  @Test
  void collect_stopsAtTheDailyBudget_logsIt_andCallsNothing() {
    for (int i = 0; i < 6; i++) {
      runLog.insert(new CollectionRunLogRepository.Run(NOW.minusSeconds(60), "US", "WORLD", 0, 0, 0, false, null, 1));
    }
    runLog.insert(new CollectionRunLogRepository.Run(Instant.parse("2026-11-06T16:59:59Z"), "US", "WORLD", 0, 0, 0, false, null, 1));
    CollectionSettings six = new CollectionSettings(SETTINGS.promptTemplate(), 3, 6);

    var outcome = collector.collect(US, HEALTH, six, KEY);

    assertThat(outcome.budgetReached()).isTrue();
    verify(gemini, never()).generate(anyString(), anyString());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_collection_run", Integer.class)).isEqualTo(7);
    assertThat(allLogs()).contains("news_collect_budget_reached used=6 limit=6");
  }

  @Test
  void collect_belowTheBudgetStillCalls() {
    runLog.insert(new CollectionRunLogRepository.Run(NOW.minusSeconds(60), "US", "WORLD", 0, 0, 0, false, null, 1));
    gemini(answer(1));

    var outcome = collector.collect(US, HEALTH, new CollectionSettings(SETTINGS.promptTemplate(), 3, 2), KEY);

    assertThat(outcome.budgetReached()).isFalse();
    assertThat(outcome.stored()).isEqualTo(1);
  }

  @Test
  void collect_keepsOnlyTheFirstNStoriesAfterFiltering() {
    gemini(answer(1, 2, 3, 4));

    var outcome = collector.collect(US, HEALTH, new CollectionSettings(SETTINGS.promptTemplate(), 2, 300), KEY);

    assertThat(outcome.stored()).isEqualTo(2);
    assertThat(outcome.stories()).isEqualTo(4);
    assertThat(jdbc.queryForList("SELECT url FROM news_story_source ORDER BY id", String.class))
        .containsExactly("https://example.test/a1", "https://example.test/a2");
    assertThat(jdbc.queryForObject("SELECT summary FROM news_summary", String.class)).contains("Title 2").doesNotContain("Title 3");
  }

  @Test
  void collect_countsRejectedStoriesAndKeepsTheValidOnes() {
    String text = "{\"sections\":{\"health\":[" + story(1) + ",{\"title\":\"Bad\",\"summary\":\"x\",\"url\":\"https://evil.test/z\"}]}}";
    gemini(text);

    var outcome = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome).isEqualTo(new CountryCategoryCollector.Outcome(false, false, false, 2, 1, 1));
    assertThat(jdbc.queryForObject("SELECT rejected FROM news_collection_run", Integer.class)).isEqualTo(1);
  }

  @Test
  void collect_noStoriesIsASuccessfulCallThatStoresNothing() {
    gemini("{\"sections\":{\"health\":[]}}");

    var outcome = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome).isEqualTo(new CountryCategoryCollector.Outcome(false, false, false, 0, 0, 0));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT failed FROM news_collection_run", Boolean.class)).isFalse();
  }

  @Test
  void collect_aMissingKeyIsRecordedAsKeyMissing() {
    when(gemini.generate(anyString(), eq(""))).thenReturn(GeminiResult.missingKey());

    var outcome = collector.collect(US, HEALTH, SETTINGS, "");

    assertThat(outcome.keyMissing()).isTrue();
    assertThat(outcome.failed()).isTrue();
    Map<String, Object> run = jdbc.queryForMap("SELECT * FROM news_collection_run");
    assertThat(run.get("ERROR")).isEqualTo("KEY_MISSING");
    assertThat(run.get("FAILED")).isEqualTo(true);
  }

  @Test
  void collect_aGeminiErrorIsRecordedWithOnlyGoogleStatus_andNotThrown() {
    when(gemini.generate(anyString(), eq(KEY))).thenThrow(new GeminiException(429, "RESOURCE_EXHAUSTED"));

    var outcome = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome.failed()).isTrue();
    assertThat(jdbc.queryForObject("SELECT error FROM news_collection_run", String.class)).isEqualTo("RESOURCE_EXHAUSTED");
    assertThat(jdbc.queryForObject("SELECT failed FROM news_collection_run", Boolean.class)).isTrue();
  }

  @Test
  void collect_anAnswerWithoutJsonIsRecordedAsInvalidAnswer() {
    gemini("Sorry, I could not find anything.");

    var outcome = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome.failed()).isTrue();
    assertThat(jdbc.queryForObject("SELECT error FROM news_collection_run", String.class)).isEqualTo("INVALID_ANSWER");
  }

  @Test
  void collect_anUnexpectedExceptionIsRecordedGenerically_andNeitherKeyNorMessageIsLoggedOrStored() {
    when(gemini.generate(anyString(), eq(KEY))).thenThrow(new IllegalStateException("boom " + KEY));

    var outcome = collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome.failed()).isTrue();
    assertThat(jdbc.queryForObject("SELECT error FROM news_collection_run", String.class)).isEqualTo("ERROR");
    assertThat(allLogs()).contains("type=IllegalStateException").doesNotContain(KEY).doesNotContain("boom");
  }

  @Test
  void collect_neverLogsTheKeyOrThePrompt() {
    gemini(answer(1));

    collector.collect(US, HEALTH, SETTINGS, KEY);

    assertThat(allLogs()).doesNotContain(KEY).doesNotContain("Local news").doesNotContain("FIXED RULES");
  }

  @Test
  void collect_aRunLogWriteFailureIsSwallowed() {
    CollectionRunLogRepository broken = mock(CollectionRunLogRepository.class);
    when(broken.countCallsSince(any())).thenReturn(0);
    org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(broken).insert(any());
    gemini(answer(1));

    var outcome = collector(drafts, broken).collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome.stored()).isEqualTo(1);
    assertThat(allLogs()).contains("news_collect_run_log_failed country=US category=HEALTH type=IllegalStateException");
  }

  @Test
  void collect_aDuplicateInsertCountsTheStoriesAsRejected() {
    NewsDraftRepository dup = mock(NewsDraftRepository.class);
    when(dup.insert(any(NewsDraft.class), anyString(), anyString(), any(Instant.class), any())).thenReturn(false);
    gemini(answer(1, 2));

    var outcome = collector(dup, runLog).collect(US, HEALTH, SETTINGS, KEY);

    assertThat(outcome).isEqualTo(new CountryCategoryCollector.Outcome(false, false, false, 2, 0, 2));
    assertThat(allLogs()).contains("news_collect_rejected country=US reason=duplicate");
  }

  @Test
  @SuppressWarnings("unchecked")
  void collect_extraSectionsAreFiledUnderTheRequestedCategory_andOnlyTheFirstDraftGetsTheUrlRows() {
    NewsDraftRepository spy = mock(NewsDraftRepository.class);
    when(spy.insert(any(NewsDraft.class), anyString(), anyString(), any(Instant.class), any())).thenReturn(true);
    gemini("{\"sections\":{\"health\":[" + story(1) + "],\"world\":[" + story(2) + "]}}");

    var outcome = collector(spy, runLog).collect(US, HEALTH, SETTINGS, KEY);

    ArgumentCaptor<NewsDraft> draft = ArgumentCaptor.forClass(NewsDraft.class);
    ArgumentCaptor<List<StoryUrl>> urls = ArgumentCaptor.forClass(List.class);
    verify(spy, org.mockito.Mockito.times(2)).insert(draft.capture(), eq("US"), eq("en"), eq(NOW), urls.capture());
    assertThat(draft.getAllValues()).extracting(NewsDraft::category).containsOnly("HEALTH");
    assertThat(urls.getAllValues().get(0)).hasSize(2);
    assertThat(urls.getAllValues().get(1)).isEmpty();
    assertThat(outcome.stored()).isEqualTo(2);
  }

  @Test
  void cap_keepsTheFirstNStoriesOfAnArraySectionAndTheirUrls() {
    List<StoryUrl> kept = List.of(new StoryUrl("h1", "u1"), new StoryUrl("h2", "u2"), new StoryUrl("h3", "u3"));

    CountryCategoryCollector.Capped capped = CountryCategoryCollector.cap(answer(1, 2, 3), kept, 2);

    assertThat(capped.json()).contains("a1").contains("a2").doesNotContain("a3");
    assertThat(capped.kept()).containsExactly(kept.get(0), kept.get(1));
  }

  @Test
  void cap_worksOnAnItemsObjectAndAcrossSections_andLeavesAShortListAlone() {
    String json = "{\"sections\":{\"stocks\":{\"items\":[" + story(1) + "]},\"world\":[" + story(2) + "],\"odd\":5}}";
    List<StoryUrl> kept = List.of(new StoryUrl("h1", "u1"), new StoryUrl("h2", "u2"));

    CountryCategoryCollector.Capped one = CountryCategoryCollector.cap(json, kept, 1);
    CountryCategoryCollector.Capped all = CountryCategoryCollector.cap(json, kept, 10);

    assertThat(one.json()).contains("a1").doesNotContain("a2");
    assertThat(one.kept()).hasSize(1);
    assertThat(all.json()).contains("a1").contains("a2");
    assertThat(all.kept()).hasSize(2);
  }

  @Test
  void cap_rejectsTextThatIsNotJson() {
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CountryCategoryCollector.cap("{nope", List.of(), 1));
  }

  @Test
  void startOfVietnamDay_isMidnightAtUtcPlusSeven() {
    assertThat(CountryCategoryCollector.startOfVietnamDay(Instant.parse("2026-11-07T16:59:59Z"))).isEqualTo(Instant.parse("2026-11-06T17:00:00Z"));
    assertThat(CountryCategoryCollector.startOfVietnamDay(Instant.parse("2026-11-07T17:00:00Z"))).isEqualTo(Instant.parse("2026-11-07T17:00:00Z"));
  }

  @Test
  void languageName_givesTheEnglishNameOrTheCodeWhenUnknown() {
    assertThat(CountryCategoryCollector.languageName("en")).isEqualTo("English");
    assertThat(CountryCategoryCollector.languageName("vi")).isEqualTo("Vietnamese");
    assertThat(CountryCategoryCollector.languageName("")).isEmpty();
    assertThat(CountryCategoryCollector.languageName(null)).isEqualTo("null");
  }

  @Test
  void categoryName_isEnglishForEnAndVietnameseOtherwise() {
    assertThat(CountryCategoryCollector.categoryName(HEALTH, "en")).isEqualTo("Health");
    assertThat(CountryCategoryCollector.categoryName(HEALTH, "vi")).isEqualTo("Sức khỏe");
    assertThat(CountryCategoryCollector.categoryName(HEALTH, "fr")).isEqualTo("Sức khỏe");
  }
}
