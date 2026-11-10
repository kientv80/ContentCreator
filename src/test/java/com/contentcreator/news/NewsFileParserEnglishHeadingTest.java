package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** English bulletins get English section headings; Vietnamese ones keep the Vietnamese headings. */
class NewsFileParserEnglishHeadingTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
  private static final String JSON = """
      {"date":"2026-11-10","sections":{
        "economy":[{"title":"A","summary":"S one.","source":"X","url":"https://example.com/a"}],
        "stocks":[{"title":"B","summary":"S two.","source":"X","url":"https://example.com/b"}],
        "world":[{"title":"C","summary":"S three.","source":"X","url":"https://example.com/c"}]}}
      """;

  private static final java.util.function.Function<String, Optional<NewsCategory>> CATEGORIES = code ->
      "ECONOMY".equals(code) ? Optional.of(new NewsCategory("ECONOMY", "x", "Kinh tế", "Economy")) : Optional.empty();

  @Test
  void anEnglishBulletinGetsEnglishHeadings() {
    List<NewsDraft> drafts = NewsFileParser.parse(JSON, TODAY, CATEGORIES, "en");
    assertThat(drafts).extracting(NewsDraft::headline)
        .containsExactlyInAnyOrder("Economy news", "Stock market news (latest session 11/10)", "World news");
  }

  @Test
  void aVietnameseBulletinKeepsItsVietnameseHeadings() {
    List<NewsDraft> drafts = NewsFileParser.parse(JSON, TODAY, CATEGORIES, "vi");
    assertThat(drafts).extracting(NewsDraft::headline)
        .containsExactlyInAnyOrder("Tin kinh tế", "Tin chứng khoán (phiên gần nhất 10/11)", "Tin thế giới");
  }
}
