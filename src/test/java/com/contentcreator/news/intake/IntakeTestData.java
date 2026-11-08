package com.contentcreator.news.intake;

import org.springframework.jdbc.core.JdbcTemplate;

/** Shared fixtures for the intake tests: countries, trusted sites and a small bulletin. */
final class IntakeTestData {

  private IntakeTestData() {}

  static void reset(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM news_story_extra");
    jdbc.update("DELETE FROM news_story_source");
    jdbc.update("DELETE FROM news_summary");
    jdbc.update("DELETE FROM country_news_site");
    jdbc.update("DELETE FROM country_news_config");
    jdbc.update("DELETE FROM country");
  }

  static void country(JdbcTemplate jdbc, String code) {
    jdbc.update("INSERT INTO country (code, name, default_language, enabled) VALUES (?, ?, 'en', TRUE)", code, "Country " + code);
  }

  static void site(JdbcTemplate jdbc, String country, String domain) {
    jdbc.update("INSERT INTO country_news_site (country_code, name, domain, sort_order) VALUES (?, ?, ?, 1)", country, domain, domain);
  }

  static String story(String title, String url) {
    return "{\"title\":\"" + title + "\",\"summary\":\"Summary of " + title + ".\",\"key_points\":[\"point one\"],"
        + "\"analysis\":\"analysis of " + title + "\",\"source\":\"News\",\"url\":\"" + url + "\"}";
  }

  /** A bulletin with the given extra top-level fields (may be empty) and one domestic section of the stories. */
  static String bulletin(String extra, String... stories) {
    return "{" + extra + "\"date\":\"2026-11-08\",\"sections\":{\"domestic\":[" + String.join(",", stories) + "]}}";
  }
}
