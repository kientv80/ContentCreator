package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsJsonParserTest {

  @Test
  void parsesAnArrayWrappedInProseAndFences() {
    String text = "Here you go:\n```json\n[{\"title\":\"Tin A\",\"summary\":\"Tóm tắt A\",\"category\":\"health\","
        + "\"source\":\"VnExpress\",\"url\":\"https://vnexpress.net/a\",\"published\":\"2026-10-20\"}]\n```";

    List<NewsItem> items = NewsJsonParser.parse(text);

    assertThat(items).hasSize(1);
    assertThat(items.get(0).title()).isEqualTo("Tin A");
    assertThat(items.get(0).sourceName()).isEqualTo("VnExpress");
    assertThat(items.get(0).publishedAt()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
  }

  @Test
  void dropsEntriesWithoutTitleSummaryOrHttpLink() {
    String text = "[{\"title\":\"\",\"summary\":\"s\",\"url\":\"https://a.vn\"},"
        + "{\"title\":\"t\",\"summary\":\"s\",\"url\":\"javascript:alert(1)\"},"
        + "{\"title\":\"t\",\"summary\":\"s\"},"
        + "{\"title\":\"ok\",\"summary\":\"s\",\"url\":\"http://a.vn/x\",\"published\":\"not a date\"}]";

    List<NewsItem> items = NewsJsonParser.parse(text);

    assertThat(items).extracting(NewsItem::title).containsExactly("ok");
    assertThat(items.get(0).publishedAt()).isNull();
  }

  @Test
  void rejectsAnswersWithoutAJsonArray() {
    assertThatThrownBy(() -> NewsJsonParser.parse("Sorry, nothing found")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsJsonParser.parse("[not json]")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsJsonParser.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
