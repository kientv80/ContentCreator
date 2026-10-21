package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SpringBootTest
class CategoryRepositoryTest {

  @Autowired JdbcTemplate jdbc;

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM news_category WHERE code IN ('HEALTH', 'OFF')");
  }

  @Test
  void findsAnActiveCategoryByCode_includingOneAddedAfterEarlierLookups() {
    CategoryRepository repository = new CategoryRepository(jdbc);
    assertThat(repository.findActive("HEALTH")).isEmpty();

    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('HEALTH', '🩺', 'Health', 'Sức khỏe', 6, TRUE)");

    assertThat(repository.findActive("HEALTH")).contains(new NewsCategory("HEALTH", "🩺", "Sức khỏe"));
    assertThat(repository.findActive("WORLD")).contains(new NewsCategory("WORLD", "🌍", "Thế giới"));
  }

  @Test
  void anInactiveOrUnknownCategoryIsNotFound_andACodeIsOnlyEverAParameter() {
    jdbc.update("INSERT INTO news_category (code, emoji, name_en, name_vi, sort_order, active) VALUES ('OFF', 'x', 'Off', 'Tắt', 7, FALSE)");
    CategoryRepository repository = new CategoryRepository(jdbc);

    assertThat(repository.findActive("OFF")).isEmpty();
    assertThat(repository.findActive("NOPE")).isEmpty();
    assertThat(repository.findActive("' OR '1'='1")).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aFailingLookupIsEmptyInsteadOfAnException() {
    JdbcTemplate broken = mock(JdbcTemplate.class);
    when(broken.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenThrow(new BadSqlGrammarException("q", "SELECT", new java.sql.SQLException("denied")));

    assertThat(new CategoryRepository(broken).findActive("WORLD")).isEmpty();
  }
}
