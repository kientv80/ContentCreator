package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsFileParserTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 1, 1);

  private static String sample() throws IOException {
    try (var in = NewsFileParserTest.class.getResourceAsStream("/bulletin/sample.json")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void theSampleBulletinBecomesOneDraftPerStoryPlusOneMarketDraft() throws IOException {
    List<NewsDraft> drafts = NewsFileParser.parse(sample(), TODAY);

    assertThat(drafts).hasSize(20);
    assertThat(drafts).allSatisfy(d -> {
      assertThat(d.headline().length()).isBetween(1, 120);
      assertThat(d.summary().length()).isBetween(1, 1000);
      assertThat(d.newsDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    });
    NewsDraft first = drafts.get(0);
    assertThat(first.headline()).isEqualTo("Lãi suất tiết kiệm giảm đồng loạt");
    assertThat(first.summary()).endsWith(" (Nguồn: VnExpress)");
    NewsDraft market = drafts.stream().filter(d -> d.headline().startsWith("Chứng khoán")).findFirst().orElseThrow();
    assertThat(market.headline()).isEqualTo("Chứng khoán ngày 20/10: VN-Index 1.691,97 điểm");
    assertThat(market.summary()).contains("VN-Index tăng 2,13 điểm").contains("(Nguồn: Dân Việt, CafeF, HDBS, TheStreet)");
  }

  @Test
  void longTextsAreCutToTheColumnLimitsAndKeepTheSource() {
    String json = "{\"date\":\"2026-10-20\",\"sections\":{\"x\":[{\"title\":\"" + "h".repeat(200) + "\",\"summary\":\"" + "s".repeat(1500)
        + "\",\"source\":\"VnExpress\"}]}}";

    NewsDraft d = NewsFileParser.parse(json, TODAY).get(0);

    assertThat(d.headline()).hasSize(120);
    assertThat(d.summary()).hasSize(1000).contains("…").endsWith(" (Nguồn: VnExpress)");
  }

  @Test
  void skipsIncompleteStoriesAndUsesTodayForABadDate_andHandlesMissingSections() {
    String json = "{\"date\":\"soon\",\"sections\":{\"x\":[{\"title\":\"\",\"summary\":\"s\"},{\"title\":\"t\"},{\"title\":\"ok\",\"summary\":\"s\"}],"
        + "\"stocks\":{\"items\":[{\"title\":\"a\"}]},\"other\":\"text\"}}";

    List<NewsDraft> drafts = NewsFileParser.parse(json, TODAY);

    assertThat(drafts).extracting(NewsDraft::headline).containsExactly("ok");
    assertThat(drafts.get(0).summary()).isEqualTo("s");
    assertThat(drafts.get(0).newsDate()).isEqualTo(TODAY);
    assertThat(NewsFileParser.parse("{}", TODAY)).isEmpty();
  }

  @Test
  void aMarketSectionWithoutIndicesStillGetsAHeadlineAndNoSourceWhenNoneIsGiven() {
    String json = "{\"date\":\"2026-10-20\",\"sections\":{\"stocks\":{\"items\":[{\"summary\":\"Thị trường đi ngang.\"},{\"title\":\"x\"}]}}}";

    NewsDraft d = NewsFileParser.parse(json, TODAY).get(0);

    assertThat(d.headline()).isEqualTo("Chứng khoán ngày 20/10");
    assertThat(d.summary()).isEqualTo("Thị trường đi ngang.");
  }

  @Test
  void rejectsFilesThatAreNotAJsonObject() {
    assertThatThrownBy(() -> NewsFileParser.parse("not json", TODAY)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsFileParser.parse("[1,2]", TODAY)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsFileParser.parse("", TODAY)).isInstanceOf(IllegalArgumentException.class);
  }
}
