package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class CollectionConfigRepositoryTest {

  @Autowired JdbcTemplate jdbc;
  CollectionConfigRepository repository;

  @BeforeEach
  void setUp() {
    repository = new CollectionConfigRepository(jdbc);
    clean();
    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('HEALTH', 'h', 'Health', 'Sức khỏe', 60, TRUE)");
    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('ECONOMY', 'e', 'Economy', 'Kinh tế', 50, TRUE)");
    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('OFFCAT', 'x', 'Off', 'Tắt', 55, FALSE)");
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM country_news_site");
    jdbc.update("DELETE FROM country_news_category");
    jdbc.update("DELETE FROM country_news_config");
    jdbc.update("DELETE FROM country");
    jdbc.update("DELETE FROM news_collection_settings");
    jdbc.update("DELETE FROM news_category WHERE code IN ('HEALTH', 'ECONOMY', 'OFFCAT')");
  }

  private void country(String code, String name, boolean collect, String language, String notes) {
    jdbc.update("INSERT INTO country (code, name, default_language, enabled) VALUES (?, ?, ?, TRUE)", code, name, language);
    jdbc.update("INSERT INTO country_news_config (country_code, collect, language, notes) VALUES (?, ?, ?, ?)", code, collect, language, notes);
  }

  @Test
  void settings_returnsTheSingleRow_orEmptyWhenMissing() {
    assertThat(repository.settings()).isEmpty();

    jdbc.update("INSERT INTO news_collection_settings (id, prompt_template, stories_per_category, calls_per_day) VALUES (1, 'T {country}', 4, 9)");

    assertThat(repository.settings()).contains(new CollectionSettings("T {country}", 4, 9));
  }

  @Test
  void settings_isReadAgainOnEveryCall() {
    jdbc.update("INSERT INTO news_collection_settings (id, prompt_template, stories_per_category, calls_per_day) VALUES (1, 'A', 3, 6)");
    assertThat(repository.settings().orElseThrow().callsPerDay()).isEqualTo(6);

    jdbc.update("UPDATE news_collection_settings SET calls_per_day = 7");

    assertThat(repository.settings().orElseThrow().callsPerDay()).isEqualTo(7);
  }

  @Test
  void enabledCountries_excludesADisabledCountry_andJoinsNameLanguageNotes() {
    country("US", "United States", true, "en", "Local news only");
    country("FR", "France", false, "fr", null);

    List<CountryConfig> countries = repository.enabledCountries();

    assertThat(countries).extracting(CountryConfig::code).containsExactly("US");
    assertThat(countries.get(0).name()).isEqualTo("United States");
    assertThat(countries.get(0).language()).isEqualTo("en");
    assertThat(countries.get(0).notes()).isEqualTo("Local news only");
  }

  @Test
  void enabledCountries_listsOnlyActiveNonOtherCategories_inSortOrder() {
    country("US", "United States", true, "en", null);
    for (String c : List.of("HEALTH", "ECONOMY", "OFFCAT", "OTHER", "WORLD")) {
      jdbc.update("INSERT INTO country_news_category (country_code, category_code) VALUES ('US', ?)", c);
    }

    CountryConfig us = repository.enabledCountries().get(0);

    assertThat(us.categories()).extracting("code").containsExactly("WORLD", "ECONOMY", "HEALTH");
    assertThat(us.categories().get(1).nameEn()).isEqualTo("Economy");
    assertThat(us.categories().get(1).nameVi()).isEqualTo("Kinh tế");
  }

  @Test
  void enabledCountries_keepsTheSitesInTheAdminsOrder_perCountry() {
    country("US", "United States", true, "en", null);
    country("GB", "United Kingdom", true, "en", null);
    jdbc.update("INSERT INTO country_news_site (country_code, name, domain, sort_order) VALUES ('US', 'Second', 'b.test', 2)");
    jdbc.update("INSERT INTO country_news_site (country_code, name, domain, sort_order) VALUES ('US', 'First', 'a.test', 1)");
    jdbc.update("INSERT INTO country_news_site (country_code, name, domain, sort_order) VALUES ('GB', 'Other', 'c.test', 1)");

    List<CountryConfig> countries = repository.enabledCountries();

    assertThat(countries).extracting(CountryConfig::code).containsExactly("GB", "US");
    assertThat(countries.get(1).sites()).containsExactly(new PromptBuilder.Site("First", "a.test"), new PromptBuilder.Site("Second", "b.test"));
    assertThat(countries.get(0).sites()).containsExactly(new PromptBuilder.Site("Other", "c.test"));
  }

  @Test
  void enabledCountries_aCountryWithNothingSelectedHasEmptyLists_andNoCountriesGivesAnEmptyList() {
    assertThat(repository.enabledCountries()).isEmpty();

    country("US", "United States", true, "en", null);

    CountryConfig us = repository.enabledCountries().get(0);
    assertThat(us.categories()).isEmpty();
    assertThat(us.sites()).isEmpty();
  }
}
