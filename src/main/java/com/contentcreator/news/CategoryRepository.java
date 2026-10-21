package com.contentcreator.news;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only access to ElderEase's {@code news_category} table (migration V42; needs SELECT on it). */
@Repository
public class CategoryRepository {

  private static final Logger log = LoggerFactory.getLogger(CategoryRepository.class);

  private final JdbcTemplate jdbc;

  public CategoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Looks up one active category. Nothing is cached, so a category an admin adds is seen on the next call.
   *
   * @param code the category code
   * @return the category, or empty when it is missing, inactive, or the lookup fails (the caller then uses OTHER)
   */
  public Optional<NewsCategory> findActive(String code) {
    try {
      List<NewsCategory> found = jdbc.query(
          "SELECT code, emoji, name_vi FROM news_category WHERE code = ? AND active = TRUE",
          (rs, i) -> new NewsCategory(rs.getString("code"), rs.getString("emoji"), rs.getString("name_vi")), code);
      return found.stream().findFirst();
    } catch (DataAccessException e) {
      log.error("news_category_lookup_failed code={} reason={}", code, e.toString());
      return Optional.empty();
    }
  }
}
