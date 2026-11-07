package com.contentcreator.news.collect;

import com.contentcreator.news.NewsCategory;
import java.util.List;

/**
 * One country the admin switched collection on for, with everything one run needs.
 *
 * @param code the two-letter country code
 * @param name the country name from the {@code country} table
 * @param language the news language code (e.g. {@code en}, {@code vi})
 * @param notes the admin's extra notes, may be null
 * @param categories the selected categories (active, not OTHER) in sort order
 * @param sites the trusted websites in the admin's order
 */
public record CountryConfig(
    String code, String name, String language, String notes, List<NewsCategory> categories, List<PromptBuilder.Site> sites) {

  public CountryConfig {
    categories = List.copyOf(categories);
    sites = List.copyOf(sites);
  }
}
