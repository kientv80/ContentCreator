package com.contentcreator.news.collect;

import com.contentcreator.news.NewsCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the collection settings the admin edits (ElderEase V62: {@code news_collection_settings},
 * {@code country_news_config}, {@code country_news_category}, {@code country_news_site}, plus {@code country} and
 * {@code news_category}). Nothing is cached, so every run sees the admin's latest edits. It only ever issues SELECT.
 */
@Repository
public class CollectionConfigRepository {

  private final JdbcTemplate jdbc;

  /** @param jdbc the shared JDBC template */
  public CollectionConfigRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Reads the global settings row.
   *
   * @return the settings, or empty when the row does not exist
   */
  public Optional<CollectionSettings> settings() {
    return jdbc.query(
            "SELECT prompt_template, stories_per_category, calls_per_day FROM news_collection_settings WHERE id = 1",
            (rs, i) -> new CollectionSettings(rs.getString("prompt_template"), rs.getInt("stories_per_category"), rs.getInt("calls_per_day")))
        .stream()
        .findFirst();
  }

  /**
   * Reads every country with collection switched on, ordered by country code. Categories are limited to active ones
   * other than OTHER, in sort order; sites keep the admin's order.
   *
   * @return the enabled countries (a country with no category or no site is still returned, with empty lists)
   */
  public List<CountryConfig> enabledCountries() {
    record Head(String code, String name, String language, String notes) {}
    List<Head> heads = jdbc.query(
        "SELECT c.code, c.name, n.language, n.notes FROM country_news_config n JOIN country c ON c.code = n.country_code "
            + "WHERE n.collect = TRUE ORDER BY c.code",
        (rs, i) -> new Head(rs.getString("code"), rs.getString("name"), rs.getString("language"), rs.getString("notes")));
    Map<String, List<NewsCategory>> categories = new LinkedHashMap<>();
    jdbc.query(
        "SELECT x.country_code, g.code, g.emoji, g.name_vi, g.name_en FROM country_news_category x JOIN news_category g ON g.code = x.category_code "
            + "WHERE g.active = TRUE AND g.code <> 'OTHER' ORDER BY g.sort_order, g.code",
        rs -> {
          categories.computeIfAbsent(rs.getString("country_code"), k -> new ArrayList<>())
              .add(new NewsCategory(rs.getString("code"), rs.getString("emoji"), rs.getString("name_vi"), rs.getString("name_en")));
        });
    Map<String, List<PromptBuilder.Site>> sites = new LinkedHashMap<>();
    jdbc.query(
        "SELECT country_code, name, domain FROM country_news_site ORDER BY sort_order, id",
        rs -> {
          sites.computeIfAbsent(rs.getString("country_code"), k -> new ArrayList<>())
              .add(new PromptBuilder.Site(rs.getString("name"), rs.getString("domain")));
        });
    return heads.stream()
        .map(h -> new CountryConfig(h.code(), h.name(), h.language(), h.notes(),
            categories.getOrDefault(h.code(), List.of()), sites.getOrDefault(h.code(), List.of())))
        .toList();
  }

  /**
   * Whether the country table has the code (any country, not only those with collection switched on).
   *
   * @param code the two-letter country code
   * @return true when a row exists
   */
  public boolean countryExists(String code) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM country WHERE code = ?", Integer.class, code);
    return count != null && count > 0;
  }

  /**
   * The trusted website domains of one country in the admin's order.
   *
   * @param code the two-letter country code
   * @return the domains, empty when the country has no list
   */
  public List<String> siteDomains(String code) {
    return jdbc.queryForList("SELECT domain FROM country_news_site WHERE country_code = ? ORDER BY sort_order, id", String.class, code);
  }
}
