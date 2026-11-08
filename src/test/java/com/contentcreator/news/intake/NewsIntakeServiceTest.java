package com.contentcreator.news.intake;

import static com.contentcreator.news.intake.IntakeTestData.bulletin;
import static com.contentcreator.news.intake.IntakeTestData.story;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "contentcreator.news.intake.token-sha256=" + "4e340c03e91d3ab6cf9ef75ca566fdf3f28357785ac750c06dbf2df2b551e7e1")
class NewsIntakeServiceTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired NewsIntakeService service;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  @AfterEach
  void clean() {
    IntakeTestData.reset(jdbc);
  }

  private NewsIntakeService.Result run(String json) throws Exception {
    return service.process(mapper.readTree(json));
  }

  @Test
  void process_storesOneUnpublishedDraftPerSectionWithCountryLanguageExtrasAndSourceRows() throws Exception {
    IntakeTestData.country(jdbc, "VN");
    String body = "{\"date\":\"2026-11-08\",\"sections\":{"
        + "\"domestic\":[" + story("A", "https://one.test/a") + "," + story("B", "https://one.test/b") + "],"
        + "\"world\":{\"items\":[" + story("C", "https://one.test/c") + "]}}}";

    NewsIntakeService.Result r = run(body);

    assertThat(r.status()).isEqualTo(NewsIntakeService.Status.CREATED);
    assertThat(r.received()).isEqualTo(3);
    assertThat(r.stored()).isEqualTo(3);
    assertThat(r.skipped()).isZero();
    assertThat(jdbc.queryForList("SELECT country_code, language, published, category FROM news_summary ORDER BY category"))
        .extracting(m -> m.get("CATEGORY") + "/" + m.get("COUNTRY_CODE") + "/" + m.get("LANGUAGE") + "/" + m.get("PUBLISHED"))
        .containsExactly("DOMESTIC/VN/vi/false", "WORLD/VN/vi/false");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_extra", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_story_source", Integer.class)).isEqualTo(3);
  }

  @Test
  void process_usesTheRequestsCountryAndLanguage() throws Exception {
    IntakeTestData.country(jdbc, "US");

    run(bulletin("\"country\":\"us\",\"language\":\"en\",", story("A", "https://one.test/a")));

    assertThat(jdbc.queryForMap("SELECT country_code, language FROM news_summary"))
        .containsEntry("COUNTRY_CODE", "US").containsEntry("LANGUAGE", "en");
  }

  @Test
  void process_dropsAStoryWhoseHostIsNotOnTheCountrysListAndReportsIt() throws Exception {
    IntakeTestData.country(jdbc, "VN");
    IntakeTestData.site(jdbc, "VN", "good.test");

    NewsIntakeService.Result r = run(bulletin("", story("A", "https://good.test/a"), story("B", "https://evil.test/b"), story("C", "http://good.test/c")));

    assertThat(r.stored()).isEqualTo(1);
    assertThat(r.skipped()).isEqualTo(2);
    assertThat(r.rejected()).containsExactly(new NewsIntakeService.Rejected("host", 2));
    assertThat(jdbc.queryForList("SELECT url FROM news_story_source", String.class)).containsExactly("https://good.test/a");
  }

  @Test
  void process_withNoSiteListChecksOnlyHttpsAndNotGoogle() throws Exception {
    IntakeTestData.country(jdbc, "VN");

    NewsIntakeService.Result r = run(bulletin("", story("A", "https://any.test/a"), story("B", "https://www.google.com/url?q=x")));

    assertThat(r.stored()).isEqualTo(1);
    assertThat(r.rejected()).containsExactly(new NewsIntakeService.Rejected("host", 1));
  }

  @Test
  void process_aSecondIdenticalPostStoresNothingAndIsAlreadyStored() throws Exception {
    IntakeTestData.country(jdbc, "VN");
    String body = bulletin("", story("A", "https://one.test/a"));
    run(body);

    NewsIntakeService.Result again = run(body);

    assertThat(again.status()).isEqualTo(NewsIntakeService.Status.ALREADY_STORED);
    assertThat(again.stored()).isZero();
    assertThat(again.rejected()).containsExactly(new NewsIntakeService.Rejected("duplicate", 1));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isEqualTo(1);
  }

  @Test
  void process_aUrlRepeatedInAnotherSectionOfTheSameBodyRollsThatDraftBackAsDuplicate() throws Exception {
    IntakeTestData.country(jdbc, "VN");
    String body = "{\"sections\":{\"domestic\":[" + story("A", "https://one.test/a") + "],"
        + "\"world\":[" + story("A2", "https://one.test/a") + "]}}";

    NewsIntakeService.Result r = run(body);

    assertThat(r.status()).isEqualTo(NewsIntakeService.Status.CREATED);
    assertThat(r.stored()).isEqualTo(1);
    assertThat(r.rejected()).containsExactly(new NewsIntakeService.Rejected("duplicate", 1));
  }

  @Test
  void process_withNoValidStoryIsNoValidStory() throws Exception {
    IntakeTestData.country(jdbc, "VN");

    assertThat(run(bulletin("", story("A", "http://one.test/a"))).status()).isEqualTo(NewsIntakeService.Status.NO_VALID_STORY);
    assertThat(run("{\"sections\":{}}").status()).isEqualTo(NewsIntakeService.Status.NO_VALID_STORY);
    assertThat(run("{\"title\":\"no sections\"}").status()).isEqualTo(NewsIntakeService.Status.NO_VALID_STORY);
    assertThat(run("{\"sections\":{\"domestic\":\"text\"}}").status()).isEqualTo(NewsIntakeService.Status.NO_VALID_STORY);
  }

  @Test
  void process_aStoryWithoutTitleIsRejectedAsEmpty() throws Exception {
    IntakeTestData.country(jdbc, "VN");

    NewsIntakeService.Result r = run(bulletin("", "{\"summary\":\"s\",\"url\":\"https://one.test/a\"}"));

    assertThat(r.status()).isEqualTo(NewsIntakeService.Status.NO_VALID_STORY);
    assertThat(r.rejected()).containsExactly(new NewsIntakeService.Rejected("empty", 1));
  }

  @Test
  void process_unknownOrMalformedCountryAndBadLanguageAreRefused() throws Exception {
    IntakeTestData.country(jdbc, "VN");

    assertThat(run(bulletin("\"country\":\"ZZ\",")).status()).isEqualTo(NewsIntakeService.Status.UNKNOWN_COUNTRY);
    assertThat(run(bulletin("\"country\":\"VNM\",")).status()).isEqualTo(NewsIntakeService.Status.UNKNOWN_COUNTRY);
    assertThat(run(bulletin("\"country\":5,")).status()).isEqualTo(NewsIntakeService.Status.UNKNOWN_COUNTRY);
    assertThat(run(bulletin("\"language\":\"vietnamese\",")).status()).isEqualTo(NewsIntakeService.Status.INVALID_LANGUAGE);
  }

  @Test
  void process_nullOrBlankCountryAndLanguageFallBackToTheDefaults() throws Exception {
    IntakeTestData.country(jdbc, "VN");

    NewsIntakeService.Result r = run(bulletin("\"country\":null,\"language\":\" \",", story("A", "https://one.test/a")));

    assertThat(r.status()).isEqualTo(NewsIntakeService.Status.CREATED);
    assertThat(jdbc.queryForMap("SELECT country_code, language FROM news_summary"))
        .containsEntry("COUNTRY_CODE", "VN").containsEntry("LANGUAGE", "vi");
  }
}
