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
      assertThat(d.summary().length()).isBetween(1, 8000);
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
  void theSampleSectionsGetTheirCategories() throws IOException {
    assertThat(NewsFileParser.parse(sample(), TODAY)).extracting(NewsDraft::category)
        .containsExactly("DOMESTIC", "WORLD", "STOCKS", "SCIENCE_TECH");
  }

  @Test
  void sectionNamesMapToCategoriesIgnoringCase_andUnknownNamesAreOther() {
    assertThat(NewsFileParser.category("domestic")).isEqualTo("DOMESTIC");
    assertThat(NewsFileParser.category("World")).isEqualTo("WORLD");
    assertThat(NewsFileParser.category("STOCKS")).isEqualTo("STOCKS");
    assertThat(NewsFileParser.category("Science_Tech")).isEqualTo("SCIENCE_TECH");
    assertThat(NewsFileParser.category("sports")).isEqualTo("OTHER");
    assertThat(NewsFileParser.category("")).isEqualTo("OTHER");
  }

  @Test
  void aMixedCaseOrUnknownSectionInAFileGetsTheMatchingCategory() {
    String json = "{\"sections\":{\"World\":[{\"summary\":\"a\"}],\"sports\":[{\"summary\":\"b\"}]}}";

    assertThat(NewsFileParser.parse(json, TODAY)).extracting(NewsDraft::category).containsExactly("WORLD", "OTHER");
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
    String story = "{\"title\":\"t\",\"summary\":\"" + "s".repeat(2500) + "\"}";
    String json = "{\"date\":\"2026-10-20\",\"sections\":{\"" + "n".repeat(200) + "\":[" + String.join(",", story, story, story, story) + "]}}";

    NewsDraft d = NewsFileParser.parse(json, TODAY).get(0);

    assertThat(d.summary().split("\n\n")).hasSize(3);
    assertThat(d.summary().length()).isLessThanOrEqualTo(8000);
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

  private static String one(String source, String url) {
    String json = "{\"sections\":{\"domestic\":[{\"title\":\"T\",\"summary\":\"s\",\"source\":\"" + source + "\",\"url\":\"" + url + "\"}]}}";
    return NewsFileParser.parse(json, java.time.LocalDate.of(2026, 10, 20)).get(0).summary();
  }

  @Test
  void anUnsafeLinkBecomesPlainTextSource() {
    for (String url : new String[] {"https://vnexpress.net@evil.example/x", "https://xn--vnexprss-x9a.net/a", "http://169.254.169.254/",
        "https://vnexpress.net/\\u202Ex", "javascript:alert(1)", "https://vnexpress.net/a b"}) {
      assertThat(one("VnExpress", url)).as(url).isEqualTo("**T**: s (Nguồn: VnExpress)");
    }
  }

  @Test
  void aDomainLikeSourceMustMatchTheLinkHost() {
    assertThat(one("vnexpress.net", "https://www.vnexpress.net/a")).isEqualTo("**T**: s [vnexpress.net](https://www.vnexpress.net/a)");
    assertThat(one("vnexpress.net", "https://evil.example/a")).isEqualTo("**T**: s (Nguồn: vnexpress.net)");
    assertThat(one("VnExpress", "https://evil.example/a")).isEqualTo("**T**: s [VnExpress](https://evil.example/a)");
  }

  private static String story(String fields) {
    return NewsFileParser.parse("{\"sections\":{\"domestic\":[{" + fields + "}]}}", TODAY).get(0).summary();
  }

  @Test
  void aPublishedTimeEndsTheByline_afterTheLinkOrTheNguonFallback() {
    String base = "\"title\":\"T\",\"summary\":\"s\",\"source\":\"VnExpress\",";
    assertThat(story(base + "\"url\":\"https://vnexpress.net/a\",\"published\":\"2026-10-20T08:47:30Z\""))
        .isEqualTo("**T**: s [VnExpress](https://vnexpress.net/a) · 15:47 20/10");
    assertThat(story(base + "\"url\":\"javascript:x\",\"published\":\"2026-10-21\""))
        .isEqualTo("**T**: s (Nguồn: VnExpress) · 21/10");
  }

  @Test
  void aStoryWithoutSourceGetsTheTimeAfterTheText_onlyWhenThereIsOne() {
    assertThat(story("\"summary\":\"s\",\"published\":\"2026-10-20T15:47+07:00\"")).isEqualTo("s · 15:47 20/10");
    assertThat(story("\"summary\":\"s\",\"published\":\"soon\"")).isEqualTo("s");
  }

  @Test
  void aMissingNullOrNonTextPublishedAddsNothing() {
    String base = "\"summary\":\"s\",\"source\":\"VnExpress\",\"url\":\"https://vnexpress.net/a\"";
    String plain = "s [VnExpress](https://vnexpress.net/a)";
    assertThat(story(base)).isEqualTo(plain);
    assertThat(story(base + ",\"published\":null")).isEqualTo(plain);
    assertThat(story(base + ",\"published\":20261020")).isEqualTo(plain);
    assertThat(story(base + ",\"published\":{\"a\":1}")).isEqualTo(plain);
    assertThat(story(base + ",\"published\":\"2026-10-20T25:00\"")).isEqualTo(plain);
  }

  @Test
  void theTimeCountsTowardTheBudget_soAStoryThatNoLongerFitsIsLeftOutWhole() {
    String text = "a".repeat(3990);
    String s1 = "{\"summary\":\"" + text + "\"}";
    String fits = "{\"summary\":\"" + "b".repeat(3998) + "\"}";
    String tooBig = "{\"summary\":\"" + "b".repeat(3998) + "\",\"published\":\"2026-10-20T15:47+07:00\"}";
    // 3990 + 2 + 3998 = 7990 fits; adding " · 15:47 20/10" (14 chars) makes 8004 > 8000
    assertThat(NewsFileParser.parse("{\"sections\":{\"d\":[" + s1 + "," + fits + "]}}", TODAY).get(0).summary()).hasSize(7990);
    assertThat(NewsFileParser.parse("{\"sections\":{\"d\":[" + s1 + "," + tooBig + "]}}", TODAY).get(0).summary()).isEqualTo(text);
  }

  @Test
  void theNewestRealBulletinKeepsItsStoriesAndAddsTimes() throws IOException {
    String json;
    try (var in = NewsFileParserTest.class.getResourceAsStream("/bulletin/ban-tin-2026-10-21.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    List<NewsDraft> drafts = NewsFileParser.parse(json, TODAY);
    assertThat(drafts).isNotEmpty().allSatisfy(d -> assertThat(d.summary().length()).isBetween(1, 8000));
    String all = String.join("\n\n", drafts.stream().map(NewsDraft::summary).toList());
    assertThat(all).contains(" · 15:47 20/10").contains(" · 00:05 21/10").contains(" · 22:20 20/10").contains(" · 06:11 21/10");
  }
}
