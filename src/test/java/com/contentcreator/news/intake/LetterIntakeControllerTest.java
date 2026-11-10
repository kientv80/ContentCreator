package com.contentcreator.news.intake;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Letters through the intake, with access by address only (no secret configured). */
@SpringBootTest(properties = {"contentcreator.news.intake.token-sha256=", "contentcreator.news.intake.allowed-cidrs=203.0.113.0/24"})
@AutoConfigureMockMvc
class LetterIntakeControllerTest {

  private static final String LETTER = "{\"letter\":\"# T\\n\\n## Bài 1: Một\\n\\nNội dung.\\n\\n## Bài 2: Hai\\n\\nNội dung hai.\"}";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM poems");
    jdbc.update("DELETE FROM users");
    jdbc.update("DELETE FROM news_story_extra");
    jdbc.update("DELETE FROM news_story_source");
    jdbc.update("DELETE FROM news_summary");
    jdbc.update("DELETE FROM country_news_site");
    jdbc.update("DELETE FROM country_news_config");
    jdbc.update("DELETE FROM country");
    jdbc.update("INSERT INTO country (code, name, default_language, enabled) VALUES ('VN', 'Vietnam', 'vi', TRUE)");
    jdbc.update("INSERT INTO users (id, username) VALUES ('vn-id', 'elderfriend_ai_vn'), ('us-id', 'elderfriend_ai_us')");
  }

  private MockHttpServletRequestBuilder from(String address, String body) {
    return post("/api/v1/news-bulletins").with(r -> {
      r.setRemoteAddr(address);
      return r;
    }).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test
  void aLetterIs201ThenTheSameLetterIs200() throws Exception {
    mvc.perform(from("203.0.113.5", LETTER)).andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("letter")).andExpect(jsonPath("$.received").value(2))
        .andExpect(jsonPath("$.stored").value(2)).andExpect(jsonPath("$.skipped").value(0))
        .andExpect(jsonPath("$.rejected").isEmpty());
    mvc.perform(from("203.0.113.5", LETTER)).andExpect(status().isOk())
        .andExpect(jsonPath("$.stored").value(0)).andExpect(jsonPath("$.skipped").value(2))
        .andExpect(jsonPath("$.rejected[0].reason").value("duplicate")).andExpect(jsonPath("$.rejected[0].count").value(2));
  }

  @Test
  void theErrorAnswers() throws Exception {
    mvc.perform(from("203.0.113.6", "{\"letter\":\"x\",\"sections\":{}}")).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("ambiguous_type"));
    mvc.perform(from("203.0.113.6", "{\"letter\":\"   \"}")).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("no_valid_post"));
    mvc.perform(from("203.0.113.6", "{\"language\":\"fr\",\"letter\":\"## A\\n\\nB\"}")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_language"));
    mvc.perform(from("203.0.113.6", "{\"date\":\"nope\",\"letter\":\"## A\\n\\nB\"}")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("invalid_body"));
    jdbc.update("DELETE FROM users WHERE id = 'us-id'");
    mvc.perform(from("203.0.113.6", "{\"language\":\"en\",\"letter\":\"## A\\n\\nB\"}")).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("writer_account_missing"));
  }

  @Test
  void anAddressOutsideTheListIs401() throws Exception {
    mvc.perform(from("198.51.100.7", LETTER)).andExpect(status().isUnauthorized());
  }

  @Test
  void aLetterAndABulletinFromTheSameAddressGoEachToTheirOwnPath() throws Exception {
    mvc.perform(from("203.0.113.7", "{\"country\":\"VN\",\"sections\":{}}")).andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("no_valid_story"));
    mvc.perform(from("203.0.113.7", LETTER)).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("letter"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poems", Integer.class)).isEqualTo(2);
  }
}
