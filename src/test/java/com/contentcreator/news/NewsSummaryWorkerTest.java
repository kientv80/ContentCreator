package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class NewsSummaryWorkerTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM news_summary");
  }

  private static String item(String headline, String date) {
    return "{\"headline\":\"" + headline + "\",\"summary\":\"Tóm tắt " + headline + " (Nguồn: VnExpress)\"" + (date == null ? "" : ",\"newsDate\":\"" + date + "\"") + "}";
  }

  private static String body(String... items) {
    return "{\"items\":[" + String.join(",", items) + "]}";
  }

  private ResultActions submit(String key, String json) throws Exception {
    var request = post("/api/v1/news-summaries").contentType(MediaType.APPLICATION_JSON).content(json);
    if (key != null) request = request.header("X-API-Key", key);
    return mvc.perform(request);
  }

  @Test
  void insertsUnpublishedVietnameseDraftsForVietnamAndSkipsKnownOnes() throws Exception {
    submit("test-secret", body(item("A", "2026-10-20"), item("A", "2026-10-20"), item("B", "2026-10-19T18:30:00Z")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.received").value(3))
        .andExpect(jsonPath("$.stored").value(2));
    submit("test-secret", body(item("A", "2026-10-20"))).andExpect(jsonPath("$.stored").value(0));

    List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM news_summary ORDER BY headline");
    assertThat(rows).hasSize(2);
    for (Map<String, Object> row : rows) {
      assertThat(row.get("PUBLISHED")).isEqualTo(false);
      assertThat(row.get("PUBLISHED_AT")).isNull();
      assertThat(row.get("COUNTRY_CODE")).isEqualTo("VN");
      assertThat(row.get("LANGUAGE")).isEqualTo("vi");
      assertThat(row.get("IMAGE_URL")).isNull();
      assertThat(row.get("ID").toString()).hasSize(36);
    }
    // 18:30 UTC on the 19th is already the 20th in Vietnam.
    assertThat(rows.get(1).get("NEWS_DATE").toString()).isEqualTo("2026-10-20");
  }

  @Test
  void aMissingOrUnreadableDateMeansTodayInVietnam() throws Exception {
    submit("test-secret", body(item("NoDate", null), item("BadDate", "yesterday-ish"))).andExpect(jsonPath("$.stored").value(2));

    Integer days = jdbc.queryForObject("SELECT COUNT(DISTINCT news_date) FROM news_summary", Integer.class);
    assertThat(days).isEqualTo(1);
  }

  @Test
  void aPlainPastDateIsKept() throws Exception {
    submit("test-secret", body(item("Old", "2026-10-01"))).andExpect(status().isCreated());

    assertThat(jdbc.queryForObject("SELECT CAST(news_date AS VARCHAR) FROM news_summary", String.class)).isEqualTo("2026-10-01");
  }

  @Test
  void needsTheRightKey() throws Exception {
    submit(null, body(item("A", null))).andExpect(status().isUnauthorized());
    submit("wrong", body(item("A", null))).andExpect(status().isUnauthorized());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
  }

  @Test
  void rejectsInvalidBodies() throws Exception {
    submit("test-secret", "{\"items\":[]}").andExpect(status().isBadRequest());
    submit("test-secret", body(item("h".repeat(121), null))).andExpect(status().isBadRequest());
    submit("test-secret", body("{\"headline\":\"h\",\"summary\":\"" + "s".repeat(1001) + "\"}")).andExpect(status().isBadRequest());
    submit("test-secret", body("{\"headline\":\"\",\"summary\":\"s\"}")).andExpect(status().isBadRequest());
    submit("test-secret", "not json").andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Integer.class)).isZero();
  }
}
