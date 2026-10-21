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
  void theSampleBulletinBecomesOneDraftPerSection() throws IOException {
    List<NewsDraft> drafts = NewsFileParser.parse(sample(), TODAY);

    assertThat(drafts).extracting(NewsDraft::headline).containsExactly(
        "🏠 Tin trong nước", "🌍 Tin thế giới", "📈 Tin chứng khoán (phiên gần nhất 20/10)", "🔬 Tin khoa học & công nghệ");
    assertThat(drafts).allSatisfy(d -> {
      assertThat(d.summary().length()).isBetween(1, 5000);
      assertThat(d.newsDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    });
    NewsDraft domestic = drafts.get(0);
    assertThat(domestic.summary().split("\n\n")).hasSize(7);
    assertThat(domestic.summary()).startsWith("**Lãi suất tiết kiệm giảm đồng loạt**: Ngày 19/10, 15 ngân hàng")
        .contains("[VnExpress](https://vnexpress.net/lai-suat-tiet-kiem-giam-dong-loat-5134403.html)");
    NewsDraft stocks = drafts.get(2);
    assertThat(stocks.summary().split("\n\n")).hasSize(5);
    assertThat(stocks.summary()).startsWith("**Chỉ số**: VN-Index tăng 2,13 điểm").contains("[CafeF](https://cafef.vn/");
  }

  @Test
  void aStoryWithoutALinkNamesItsSource_andTheMarkupCharactersAreRemovedFromTheTexts() {
    String json = "{\"date\":\"2026-10-20\",\"sections\":{\"domestic\":["
        + "{\"title\":\"A *b* [c]\",\"summary\":\"Có **đậm** và [nhãn](x)\",\"source\":\"VnExpress\"},"
        + "{\"summary\":\"Không tiêu đề\",\"source\":\"Báo X\",\"url\":\"javascript:alert(1)\"},"
        + "{\"title\":\"Link (có ngoặc)\",\"summary\":\"s\",\"source\":\"Y\",\"url\":\"https://a.vn/x_(1)\"},"
        + "{\"title\":\"Không nguồn\",\"summary\":\"s\"}]}}";

    String[] paragraphs = NewsFileParser.parse(json, TODAY).get(0).summary().split("\n\n");

    assertThat(paragraphs).containsExactly(
        "**A b (c)**: Có đậm và (nhãn)(x) (Nguồn: VnExpress)",
        "Không tiêu đề (Nguồn: Báo X)",
        "**Link (có ngoặc)**: s [Y](https://a.vn/x_(1%29)",
        "**Không nguồn**: s");
  }

  @Test
  void storiesThatDoNotFitTheColumnAreLeftOutWholeAndTheHeadlineIsCut() {
    String story = "{\"title\":\"t\",\"summary\":\"" + "s".repeat(1500) + "\"}";
    String json = "{\"date\":\"2026-10-20\",\"sections\":{\"" + "n".repeat(200) + "\":[" + String.join(",", story, story, story, story) + "]}}";

    NewsDraft d = NewsFileParser.parse(json, TODAY).get(0);

    assertThat(d.summary().split("\n\n")).hasSize(3);
    assertThat(d.summary().length()).isLessThanOrEqualTo(5000);
    assertThat(d.headline()).hasSize(120).startsWith("📰 Tin nnnn");
  }

  @Test
  void anUnknownSectionGetsAGenericHeading_emptySectionsAreSkipped_andABadDateMeansToday() {
    String json = "{\"date\":\"soon\",\"sections\":{\"health_news\":[{\"title\":\"t\",\"summary\":\"s\"}],"
        + "\"empty\":[],\"junk\":[{\"title\":\"x\"}],\"text\":\"abc\",\"stocks\":{\"items\":[{\"summary\":\"Đi ngang.\"}]}}}";

    List<NewsDraft> drafts = NewsFileParser.parse(json, TODAY);

    assertThat(drafts).extracting(NewsDraft::headline).containsExactly("📰 Tin health news", "📈 Tin chứng khoán (phiên gần nhất 01/01)");
    assertThat(drafts).extracting(NewsDraft::newsDate).containsOnly(TODAY);
    assertThat(NewsFileParser.parse("{}", TODAY)).isEmpty();
  }

  @Test
  void theStocksHeadingUsesItsSessionDate() {
    String json = "{\"date\":\"2026-10-21\",\"sections\":{\"stocks\":{\"session_date\":\"2026-10-20\",\"items\":[{\"summary\":\"x\"}]}}}";

    NewsDraft d = NewsFileParser.parse(json, TODAY).get(0);

    assertThat(d.headline()).isEqualTo("📈 Tin chứng khoán (phiên gần nhất 20/10)");
    assertThat(d.newsDate()).isEqualTo(LocalDate.of(2026, 10, 21));
  }

  @Test
  void rejectsFilesThatAreNotAJsonObject() {
    assertThatThrownBy(() -> NewsFileParser.parse("not json", TODAY)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsFileParser.parse("[1,2]", TODAY)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> NewsFileParser.parse("", TODAY)).isInstanceOf(IllegalArgumentException.class);
  }
}
