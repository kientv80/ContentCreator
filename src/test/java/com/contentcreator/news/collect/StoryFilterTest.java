package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.contentcreator.news.StoryTitle;
import com.contentcreator.news.StoryUrl;
import com.contentcreator.news.collect.StoryFilter.Reason;
import com.contentcreator.news.collect.StoryFilter.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class StoryFilterTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> ALLOWED = List.of("example.test", "www.sample.co.uk");
  private static final List<String> GROUNDED = List.of("example.test", "news.sample.co.uk");

  private final Set<String> alreadyStored = new HashSet<>();
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  /** Titles already stored, with the instant each was stored; keyed by "country|titleHash". */
  private final java.util.Map<String, Instant> titlesStored = new java.util.HashMap<>();
  private final SeenChecker checker = new SeenChecker() {
    @Override
    public boolean seenUrl(String country, String hash) {
      return "TL".equals(country) && alreadyStored.contains(hash);
    }

    @Override
    public boolean seenTitle(String country, String titleHash, Instant since) {
      Instant at = titlesStored.get(country + "|" + titleHash);
      return at != null && !at.isBefore(since);
    }
  };

  private static String story(String url, String title, String summary) {
    return "{\"title\":" + q(title) + ",\"summary\":" + q(summary) + ",\"url\":" + q(url) + "}";
  }

  private static String q(String s) {
    return s == null ? "null" : "\"" + s + "\"";
  }

  private static String answer(String... stories) {
    return "{\"date\":\"2026-11-07\",\"sections\":{\"HEALTH\":[" + String.join(",", stories) + "]}}";
  }

  private Result run(boolean grounding, String answer) {
    return new StoryFilter(checker, grounding, 7, CLOCK).filter(answer, "TL", ALLOWED, GROUNDED);
  }

  private int rejected(Result r, Reason reason) {
    return r.rejected().get(reason);
  }

  @Test
  void filterKeepsAnAllowListedGroundedHttpsStoryAndRecordsItsUrlHash() throws Exception {
    Result r = run(true, answer(story("https://www.example.test/a/1/", "T", "S")));

    assertThat(r.kept()).containsExactly(new StoryUrl(StoryFilter.hash("https://www.example.test/a/1"), "https://www.example.test/a/1/", StoryTitle.hash("T")));
    assertThat(JSON.readTree(r.json()).at("/sections/HEALTH")).hasSize(1);
    assertThat(JSON.readTree(r.json()).path("date").asText()).isEqualTo("2026-11-07");
    assertThat(r.rejected().values()).containsOnly(0);
  }

  @Test
  void filterRejectsNonHttpsUserInfoPortAndMalformedUrlsAsHost() {
    Result r = run(false, answer(
        story("http://example.test/a", "T", "S"),
        story("https://user@example.test/a", "T", "S"),
        story("https://example.test:8443/a", "T", "S"),
        story("https://example.test\\\\@evil.test/a", "T", "S"),
        story("ftp://example.test/a", "T", "S"),
        story(null, "T", "S"),
        "\"just text\""));

    assertThat(r.kept()).isEmpty();
    assertThat(rejected(r, Reason.HOST)).isEqualTo(7);
  }

  @Test
  void filterAcceptsExplicitPort443AndUpperCaseHostAndTrailingDot() {
    Result r = run(false, answer(story("https://EXAMPLE.test.:443/a", "T", "S")));

    assertThat(r.kept()).hasSize(1);
  }

  @Test
  void filterRejectsGoogleAndRedirectHosts() {
    Result r = run(false, answer(
        story("https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc", "T", "S"),
        story("https://www.google.com/url?q=https://example.test/a", "T", "S"),
        story("https://google.com/a", "T", "S")));

    assertThat(rejected(r, Reason.HOST)).isEqualTo(3);
  }

  @Test
  void filterAcceptsSubdomainsButNotLookAlikeSuffixesOrIdnHosts() {
    Result r = run(false, answer(
        story("https://m.news.example.test/a", "T", "S"),
        story("https://evil-example.test/a", "T", "S"),
        story("https://example.test.evil.test/a", "T", "S"),
        story("https://exämple.test/a", "T", "S"),
        story("https://xn--exmple-cua.test/a", "T", "S")));

    assertThat(r.kept()).extracting(StoryUrl::url).containsExactly("https://m.news.example.test/a");
    assertThat(rejected(r, Reason.HOST)).isEqualTo(4);
  }

  @Test
  void filterRequiresTheRegistrableDomainToBeGroundedWhenSwitchedOn() {
    String notGrounded = story("https://other.test/a", "T", "S");
    List<String> allowed = List.of("example.test", "other.test");

    Result on = new StoryFilter(checker, true, 7, CLOCK).filter(answer(notGrounded), "TL", allowed, GROUNDED);
    Result off = new StoryFilter(checker, false, 7, CLOCK).filter(answer(notGrounded), "TL", allowed, GROUNDED);

    assertThat(on.kept()).isEmpty();
    assertThat(rejected(on, Reason.HOST)).isEqualTo(1);
    assertThat(off.kept()).hasSize(1);
  }

  @Test
  void filterMatchesGroundedDomainsByRegistrableDomainIncludingCountrySuffixes() {
    Result r = run(true, answer(
        story("https://www.sample.co.uk/a", "T", "S"), // grounded as news.sample.co.uk -> sample.co.uk
        story("https://example.test/b", "T2", "S")));

    assertThat(r.kept()).hasSize(2);
  }

  @Test
  void filterIgnoresGroundingEntriesThatAreNotDomains() {
    Result r = new StoryFilter(checker, true, 7, CLOCK).filter(answer(story("https://example.test/a", "T", "S")), "TL",
        ALLOWED, List.of("Some Page Title", "  ", "https://x"));

    assertThat(r.kept()).isEmpty();
  }

  @Test
  void filterRejectsAnUrlAlreadyStoredForTheCountryAsDuplicateButNotForAnotherCountry() {
    alreadyStored.add(StoryFilter.hash("https://example.test/a"));
    String a = story("https://EXAMPLE.test/a/#top", "T", "S");

    Result same = run(true, answer(a));
    Result other = new StoryFilter(checker, true, 7, CLOCK).filter(answer(a), "XX", ALLOWED, GROUNDED);

    assertThat(same.kept()).isEmpty();
    assertThat(rejected(same, Reason.DUPLICATE)).isEqualTo(1);
    assertThat(other.kept()).hasSize(1);
  }

  @Test
  void filterRejectsTheSameUrlTwiceInOneAnswerKeepingTheFirst() {
    Result r = run(true, answer(story("https://example.test/a", "T1", "S"), story("https://example.test/a/", "T2", "S")));

    assertThat(r.kept()).hasSize(1);
    assertThat(rejected(r, Reason.DUPLICATE)).isEqualTo(1);
  }

  @Test
  void filterRejectsMissingOrBlankTitleOrSummaryAsEmptyWithoutBlockingTheUrlForLaterStories() {
    Result r = run(true, answer(
        story("https://example.test/a", null, "S"),
        story("https://example.test/b", "  ", "S"),
        story("https://example.test/c", "T", ""),
        "{\"title\":5,\"summary\":\"S\",\"url\":\"https://example.test/d\"}",
        story("https://example.test/a", "T", "S")));

    assertThat(rejected(r, Reason.EMPTY)).isEqualTo(4);
    assertThat(r.kept()).extracting(StoryUrl::url).containsExactly("https://example.test/a");
  }

  @Test
  void filterAppliesTheChainInOrderHostThenDuplicateThenEmpty() {
    alreadyStored.add(StoryFilter.hash("https://example.test/dup"));

    Result r = run(true, answer(
        story("http://example.test/x", null, null),
        story("https://example.test/dup", null, null)));

    assertThat(rejected(r, Reason.HOST)).isEqualTo(1);
    assertThat(rejected(r, Reason.DUPLICATE)).isEqualTo(1);
    assertThat(rejected(r, Reason.EMPTY)).isZero();
  }

  @Test
  void filterHandlesObjectSectionsWithItemsAndSkipsSectionsWithoutAStoryArray() throws Exception {
    String json = "{\"sections\":{\"STOCKS\":{\"session_date\":\"2026-11-06\",\"items\":["
        + story("https://example.test/a", "T", "S") + "," + story("http://example.test/b", "T", "S") + "]},"
        + "\"ODD\":{\"note\":1},\"TXT\":\"x\"}}";

    Result r = run(true, json);

    JsonNode out = JSON.readTree(r.json());
    assertThat(out.at("/sections/STOCKS/items")).hasSize(1);
    assertThat(out.at("/sections/STOCKS/session_date").asText()).isEqualTo("2026-11-06");
    assertThat(out.at("/sections/ODD/note").asInt()).isEqualTo(1);
  }

  @Test
  void filterToleratesCodeFencesAndAnAnswerWithoutSections() {
    assertThat(run(true, "```json\n" + answer(story("https://example.test/a", "T", "S")) + "\n```").kept()).hasSize(1);
    assertThat(run(true, "{\"sections\":[]}").kept()).isEmpty();
    assertThat(run(true, "{}").kept()).isEmpty();
  }

  @Test
  void filterThrowsWhenTheAnswerIsNotAJsonObject() {
    for (String bad : new String[] {null, "no json here", "} {", "{not json}", "{}"}.length == 0 ? new String[0] : new String[] {null, "no json here", "} {", "{not json}"}) {
      assertThatThrownBy(() -> run(true, bad)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void filterLogsOneRejectedLinePerDroppedStoryWithCountryAndReasonOnly() {
    Logger logger = (Logger) LoggerFactory.getLogger(StoryFilter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      run(true, answer(story("http://example.test/a", "T", "S"), story("https://example.test/b", "", "S")));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly("news_collect_rejected country=TL reason=host", "news_collect_rejected country=TL reason=empty");
  }

  @Test
  void hostReturnsTheLowerCaseHostOrNull() {
    assertThat(StoryFilter.host(" https://A.B.test/x?y=1#z ")).isEqualTo("a.b.test");
    assertThat(StoryFilter.host("https://localhost/x")).isNull();
    assertThat(StoryFilter.host("https://-bad.test/x")).isNull();
    assertThat(StoryFilter.host("https:///x")).isNull();
    assertThat(StoryFilter.host(null)).isNull();
  }

  @Test
  void isGoogleMatchesGoogleComAndItsSubdomainsOnly() {
    assertThat(StoryFilter.isGoogle("google.com")).isTrue();
    assertThat(StoryFilter.isGoogle("vertexaisearch.cloud.google.com")).isTrue();
    assertThat(StoryFilter.isGoogle("notgoogle.com")).isFalse();
  }

  @Test
  void onAllowListUsesLabelBoundaries() {
    assertThat(StoryFilter.onAllowList("vnexpress.net", List.of("vnexpress.net"))).isTrue();
    assertThat(StoryFilter.onAllowList("e.vnexpress.net", List.of("vnexpress.net"))).isTrue();
    assertThat(StoryFilter.onAllowList("evil-vnexpress.net", List.of("vnexpress.net"))).isFalse();
    assertThat(StoryFilter.onAllowList("a.test", List.of())).isFalse();
  }

  @Test
  void registrableDomainHandlesPlainAndCountrySuffixHosts() {
    assertThat(StoryFilter.registrableDomain("a.b.example.test")).isEqualTo("example.test");
    assertThat(StoryFilter.registrableDomain("example.test")).isEqualTo("example.test");
    assertThat(StoryFilter.registrableDomain("news.sample.co.uk")).isEqualTo("sample.co.uk");
    assertThat(StoryFilter.registrableDomain("a.example.com.vn")).isEqualTo("example.com.vn");
    assertThat(StoryFilter.registrableDomain("localhost")).isEqualTo("localhost");
  }

  @Test
  void normalizeLowercasesTheHostAndDropsPortFragmentAndTrailingSlashesButKeepsTheQuery() {
    assertThat(StoryFilter.normalize("https://EXAMPLE.test:443/A/b//#frag")).isEqualTo("https://example.test/A/b");
    assertThat(StoryFilter.normalize("https://example.test/")).isEqualTo("https://example.test");
    assertThat(StoryFilter.normalize("https://example.test")).isEqualTo("https://example.test");
    assertThat(StoryFilter.normalize("https://example.test/a?id=7&x=1")).isEqualTo("https://example.test/a?id=7&x=1");
  }

  @Test
  void hashIsTheSha256HexOfTheText() {
    assertThat(StoryFilter.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(StoryFilter.hash("https://example.test/a")).hasSize(64).matches("[0-9a-f]+");
  }

  @Test
  void collectPropertiesDefaultToRequiringGrounding() {
    assertThat(new CollectProperties(true, 7).requireGrounding()).isTrue();
    assertThat(new CollectProperties(false, 3).requireGrounding()).isFalse();
    assertThat(new CollectProperties(false, 3).titleDedupeDays()).isEqualTo(3);
  }

  @Test
  void filterWithSkipSiteListKeepsAnyHttpsHostButStillRejectsGoogleAndHttp() {
    String a = answer(story("https://other.test/a", "T", "S"), story("https://www.google.com/url?q=x", "T", "S"), story("http://other.test/b", "T", "S"));

    Result r = new StoryFilter(checker, false, 7, CLOCK).filter(a, "TL", List.of(), List.of(), true);

    assertThat(r.kept()).extracting(StoryUrl::url).containsExactly("https://other.test/a");
    assertThat(rejected(r, Reason.HOST)).isEqualTo(2);
    assertThat(new StoryFilter(checker, false, 7, CLOCK).filter(a, "TL", List.of(), List.of()).kept()).isEmpty();
  }

  @Test
  void filterRejectsATitleStoredWithinTheWindowEvenInOtherCaseAndAccentsButNotAnotherCountry() {
    titlesStored.put("TL|" + com.contentcreator.news.StoryTitle.hash("Thời tiết hôm nay"), NOW.minusSeconds(6 * 86400));
    String a = story("https://example.test/new", "THOI TIET, hom nay!", "S");

    Result same = run(true, answer(a));
    Result other = new StoryFilter(checker, true, 7, CLOCK).filter(answer(a), "XX", ALLOWED, GROUNDED);

    assertThat(same.kept()).isEmpty();
    assertThat(rejected(same, Reason.DUPLICATE)).isEqualTo(1);
    assertThat(other.kept()).hasSize(1);
  }

  @Test
  void filterKeepsATitleStoredBeforeTheWindow() {
    titlesStored.put("TL|" + com.contentcreator.news.StoryTitle.hash("Same title"), NOW.minusSeconds(8 * 86400));

    Result r = run(true, answer(story("https://example.test/new", "Same title", "S")));

    assertThat(r.kept()).hasSize(1);
    assertThat(rejected(r, Reason.DUPLICATE)).isZero();
  }

  @Test
  void filterRejectsTheSameNormalizedTitleTwiceInOneAnswerKeepingTheFirst() {
    Result r = run(true, answer(story("https://example.test/a", "Giá vàng", "S"), story("https://example.test/b", "gia  VANG.", "S")));

    assertThat(r.kept()).extracting(StoryUrl::url).containsExactly("https://example.test/a");
    assertThat(rejected(r, Reason.DUPLICATE)).isEqualTo(1);
  }

  @Test
  void filterGivesKeptUrlsTheirTitleHashAndTreatsUrlsWithAndWithoutTrackingAsOne() {
    Result r = run(true, answer(story("https://example.test/a?utm_source=x&id=1", "Title A", "S"),
        story("https://example.test/a?id=1&fbclid=z", "Title B", "S")));

    assertThat(r.kept()).hasSize(1);
    assertThat(r.kept().get(0).titleHash()).isEqualTo(com.contentcreator.news.StoryTitle.hash("Title A"));
    assertThat(r.kept().get(0).hash()).isEqualTo(StoryFilter.hash("https://example.test/a?id=1"));
    assertThat(rejected(r, Reason.DUPLICATE)).isEqualTo(1);
  }
}
