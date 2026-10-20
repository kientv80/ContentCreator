package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NewsSummaryWorkerTest {

  static final AtomicReference<List<NewsItem>> NEXT = new AtomicReference<>(List.of());
  static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

  @TestConfiguration
  static class Fake {
    @Bean
    @Primary
    NewsCollector fakeCollector() {
      return max -> {
        if (FAIL.get() != null) throw FAIL.get();
        return NEXT.get();
      };
    }
  }

  @Autowired NewsSummaryWorker worker;
  @Autowired NewsSummaryRepository repository;
  @Autowired NewsSummaryService service;
  @Autowired MockMvc mvc;

  @BeforeEach
  void reset() {
    repository.deleteAll();
    FAIL.set(null);
    NEXT.set(List.of());
  }

  private static NewsItem item(String title, String url, Instant published) {
    return new NewsItem(title, "Tóm tắt " + title, "society", "VnExpress", url, published);
  }

  @Test
  void storesNewStoriesAndSkipsKnownLinks() {
    NEXT.set(List.of(item("A", "https://x.vn/a", null), item("A again", "https://x.vn/a", null), item("B", "https://x.vn/b", null)));

    assertThat(worker.runOnce()).isEqualTo(2);
    assertThat(worker.runOnce()).isEqualTo(0);
    assertThat(repository.count()).isEqualTo(2);
  }

  @Test
  void aFailedCollectionStoresNothingAndDoesNotThrow() {
    FAIL.set(new IllegalStateException("boom"));

    assertThat(worker.runOnce()).isEqualTo(-1);
    assertThat(repository.count()).isZero();
  }

  @Test
  void purgesStoriesCollectedLongAgo() {
    NEXT.set(List.of(item("A", "https://x.vn/a", null)));
    worker.runOnce();

    assertThat(service.purgeOlderThan(0)).isEqualTo(1);
    assertThat(repository.count()).isZero();
  }

  @Test
  void theApiListsNewestFirstAndCapsTheSize() throws Exception {
    NEXT.set(List.of(
        item("Old", "https://x.vn/old", Instant.parse("2026-10-18T00:00:00Z")),
        item("New", "https://x.vn/new", Instant.parse("2026-10-20T00:00:00Z"))));
    worker.runOnce();

    mvc.perform(get("/api/v1/news-summaries?size=1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].title").value("New"))
        .andExpect(jsonPath("$[0].language").value("vi"));
    mvc.perform(get("/api/v1/news-summaries?page=0&size=1")).andExpect(jsonPath("$.length()").value(1));
  }
}
