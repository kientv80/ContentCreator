package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NewsSummaryWorkerTest {

  @Autowired NewsSummaryWorker worker;
  @Autowired NewsSummaryRepository repository;
  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    repository.deleteAll();
  }

  private static String item(String title, String url, String published) {
    return "{\"title\":\"" + title + "\",\"summary\":\"Tóm tắt " + title + "\",\"category\":\"society\",\"source\":\"VnExpress\",\"url\":\"" + url
        + "\"" + (published == null ? "" : ",\"published\":\"" + published + "\"") + "}";
  }

  private static String body(String... items) {
    return "{\"items\":[" + String.join(",", items) + "]}";
  }

  private org.springframework.test.web.servlet.ResultActions publish(String key, String json) throws Exception {
    var request = post("/api/v1/news-summaries").contentType(MediaType.APPLICATION_JSON).content(json);
    if (key != null) request = request.header("X-API-Key", key);
    return mvc.perform(request);
  }

  @Test
  void publishInsertsNewStoriesAndSkipsKnownLinks() throws Exception {
    publish("test-secret", body(item("A", "https://x.vn/a", null), item("A again", "https://x.vn/a", null), item("B", "https://x.vn/b", "2026-10-20")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.received").value(3))
        .andExpect(jsonPath("$.stored").value(2));
    publish("test-secret", body(item("A", "https://x.vn/a", null))).andExpect(jsonPath("$.stored").value(0));
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void publishNeedsTheRightKey() throws Exception {
    publish(null, body(item("A", "https://x.vn/a", null))).andExpect(status().isUnauthorized());
    publish("wrong", body(item("A", "https://x.vn/a", null))).andExpect(status().isUnauthorized());
    assertThat(repository.count()).isZero();
  }

  @Test
  void publishRejectsInvalidBodies() throws Exception {
    publish("test-secret", "{\"items\":[]}").andExpect(status().isBadRequest());
    publish("test-secret", body(item("A", "javascript:alert(1)", null))).andExpect(status().isBadRequest());
    publish("test-secret", body("{\"title\":\"\",\"summary\":\"s\",\"url\":\"https://x.vn\"}")).andExpect(status().isBadRequest());
    publish("test-secret", "not json").andExpect(status().isBadRequest());
    assertThat(repository.count()).isZero();
  }

  @Test
  void purgeRemovesStoriesCollectedBeforeTheRetentionWindow() {
    worker.publish(java.util.List.of(new NewsItem("A", "s", null, null, "https://x.vn/a", null)));
    worker.purgeOld();
    assertThat(repository.count()).isEqualTo(1);
    // A story collected now is inside the 30-day window; one collected long ago is not.
    var old = new NewsSummary(new NewsItem("Old", "s", null, null, "https://x.vn/old", null), "h-old", java.time.Instant.now().minus(java.time.Duration.ofDays(40)));
    repository.save(old);
    worker.purgeOld();
    assertThat(repository.count()).isEqualTo(1);
  }

  @Test
  void theApiListsNewestFirstAndCapsTheSize() throws Exception {
    publish("test-secret", body(item("Old", "https://x.vn/old", "2026-10-18"), item("New", "https://x.vn/new", "2026-10-20")));

    mvc.perform(get("/api/v1/news-summaries?size=1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].title").value("New"))
        .andExpect(jsonPath("$[0].language").value("vi"));
    mvc.perform(get("/api/v1/news-summaries?page=0&size=1")).andExpect(jsonPath("$.length()").value(1));
  }
}
