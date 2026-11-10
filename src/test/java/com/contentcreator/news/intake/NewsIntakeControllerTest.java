package com.contentcreator.news.intake;

import static com.contentcreator.news.intake.IntakeTestData.bulletin;
import static com.contentcreator.news.intake.IntakeTestData.story;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "contentcreator.news.intake.token-sha256=" + NewsIntakeControllerTest.HASH)
@AutoConfigureMockMvc
class NewsIntakeControllerTest {

  static final String TOKEN = "fake-intake-token-0000";
  /** sha256("fake-intake-token-0000"), computed with sha256sum; a test below also checks it against the code. */
  static final String HASH = "4e340c03e91d3ab6cf9ef75ca566fdf3f28357785ac750c06dbf2df2b551e7e1";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  private ListAppender<ILoggingEvent> logs;
  private Logger root;

  @BeforeEach
  void setUp() {
    IntakeTestData.reset(jdbc);
    IntakeTestData.country(jdbc, "VN");
    root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    logs = new ListAppender<>();
    logs.start();
    root.addAppender(logs);
    root.setLevel(Level.DEBUG);
  }

  @AfterEach
  void tearDown() {
    root.detachAppender(logs);
    IntakeTestData.reset(jdbc);
  }

  private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger(1);
  /** A distinct client address per test, so the shared in-memory rate limiter does not carry counts between tests. */
  private String address = "10.1.0." + NEXT.getAndIncrement();

  private MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder b) {
    return b.with(r -> {
      r.setRemoteAddr(address);
      return r;
    });
  }

  private MockHttpServletRequestBuilder valid(String body) {
    return from(post("/api/v1/news-bulletins")).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private List<String> logLines() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  @Test
  void theConfiguredHashIsTheSha256OfTheFakeToken() throws Exception {
    assertThat(IntakeAuthFilterTest.sha256(TOKEN)).isEqualTo(HASH);
  }

  @Test
  void ac429_aValidTokenAndBulletinReturns201AndUnpublishedDraftsWithCountryLanguageAndExtras() throws Exception {
    IntakeTestData.country(jdbc, "US");

    mvc.perform(valid(bulletin("\"country\":\"US\",\"language\":\"en\",", story("A", "https://one.test/a"), story("B", "https://one.test/b"))))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("news"))
        .andExpect(jsonPath("$.received").value(2))
        .andExpect(jsonPath("$.stored").value(2))
        .andExpect(jsonPath("$.skipped").value(0))
        .andExpect(jsonPath("$.rejected").isEmpty());

    assertThat(jdbc.queryForMap("SELECT country_code, language, published FROM news_summary"))
        .containsEntry("COUNTRY_CODE", "US").containsEntry("LANGUAGE", "en").containsEntry("PUBLISHED", false);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isEqualTo(2);
    assertThat(logLines()).anyMatch(l -> l.equals("news_intake_received country=US sections=1 stories=2"))
        .anyMatch(l -> l.equals("news_intake_stored country=US drafts=1 stories=2"));
  }

  @Test
  void ac430_aMissingOrWrongTokenReturns401WithAnEmptyBodyStoresNothingAndLogsNoToken() throws Exception {
    String body = bulletin("", story("A", "https://one.test/a"));

    mvc.perform(from(post("/api/v1/news-bulletins")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    mvc.perform(from(post("/api/v1/news-bulletins")).header("Authorization", "Bearer fake-intake-token-0001")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    mvc.perform(from(post("/api/v1/news-bulletins")).header("Authorization", "Bearer " + TOKEN)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());
    jdbc.update("DELETE FROM news_story_source");
    jdbc.update("DELETE FROM news_story_extra");
    jdbc.update("DELETE FROM news_summary");

    assertThat(logLines()).filteredOn(l -> l.startsWith("news_intake_unauthorized")).hasSize(2).allMatch(
        l -> l.startsWith("news_intake_unauthorized ip=" + address + " reason="));
    assertThat(logLines()).noneMatch(l -> l.contains(TOKEN) || l.contains(HASH) || l.contains("fake-intake-token-0001"));
  }

  @Test
  void ac430_aRejectedRequestStoresNothing() throws Exception {
    mvc.perform(from(post("/api/v1/news-bulletins")).header("Authorization", "Bearer nope")
            .contentType(MediaType.APPLICATION_JSON).content(bulletin("", story("A", "https://one.test/a"))))
        .andExpect(status().isUnauthorized());

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source", Integer.class)).isZero();
  }

  @Test
  void ac431_postingTheSameBulletinTwiceStoresNothingTheSecondTimeAndAnswers200() throws Exception {
    String body = bulletin("", story("A", "https://one.test/a"));
    mvc.perform(valid(body)).andExpect(status().isCreated());

    mvc.perform(valid(body)).andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(0))
        .andExpect(jsonPath("$.rejected[0].reason").value("duplicate"));

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(1);
  }

  @Test
  void ac432_aStoryFromAHostNotOnTheCountrysListIsDropped() throws Exception {
    IntakeTestData.site(jdbc, "VN", "good.test");

    mvc.perform(valid(bulletin("", story("A", "https://good.test/a"), story("B", "https://evil.test/b"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.stored").value(1))
        .andExpect(jsonPath("$.skipped").value(1))
        .andExpect(jsonPath("$.rejected[0].reason").value("host"))
        .andExpect(jsonPath("$.rejected[0].count").value(1));
    assertThat(logLines()).contains("news_intake_rejected reason=host count=1");
  }

  @Test
  void ac433_anUnknownCountryReturns400WithTheMessage() throws Exception {
    mvc.perform(valid(bulletin("\"country\":\"ZZ\",", story("A", "https://one.test/a"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("unknown_country"))
        .andExpect(jsonPath("$.message").value("Unknown country."));
    assertThat(logLines()).contains("news_intake_rejected reason=unknown_country");
  }

  @Test
  void ac433_aBodyOverTwoMegabytesReturns413ByContentLengthAndByBoundedRead() throws Exception {
    String big = "{\"pad\":\"" + "x".repeat(2_000_001) + "\"}";

    mvc.perform(valid(big)).andExpect(status().isPayloadTooLarge());
    mvc.perform(valid(big).header("Transfer-Encoding", "chunked")).andExpect(status().isPayloadTooLarge());
    assertThat(logLines()).contains("news_intake_rejected reason=too_large");
  }

  @Test
  void aBodyThatIsNotAJsonObjectReturns400() throws Exception {
    mvc.perform(valid("[1,2]")).andExpect(status().isBadRequest());
    mvc.perform(valid("not json")).andExpect(status().isBadRequest());
    mvc.perform(valid("")).andExpect(status().isBadRequest());
    assertThat(logLines()).contains("news_intake_rejected reason=not_a_json_object");
  }

  @Test
  void aBodyWithNoValidStoryReturns422() throws Exception {
    mvc.perform(valid(bulletin("", story("A", "http://one.test/a"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("no_valid_story"));
  }

  @Test
  void anUnknownLanguageReturns400() throws Exception {
    mvc.perform(valid(bulletin("\"language\":\"xx-yy\",", story("A", "https://one.test/a"))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_language"));
  }

  @Test
  void the31stRequestInAnHourFromOneAddressReturns429WithRetryAfter() throws Exception {
    for (int i = 0; i < 30; i++) {
      mvc.perform(from(post("/api/v1/news-bulletins")).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
    }

    mvc.perform(valid(bulletin("", story("A", "https://one.test/a"))))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
  }
}
