package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoryExtrasTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode json(String s) throws Exception {
    return MAPPER.readTree(s);
  }

  @Test
  void keyPointsFromAList_areTrimmedAndBlanksDropped() throws Exception {
    assertThat(StoryExtras.keyPoints(json("[\" a \", \"\", \"  \", \"b\", 5, null]"))).containsExactly("a", "b");
  }

  @Test
  void keyPointsFromOneStringWithLines_splitOnLineBreaks() throws Exception {
    assertThat(StoryExtras.keyPoints(json("\"a\\nb\\r\\n\\n c \""))).containsExactly("a", "b", "c");
  }

  @Test
  void keyPointsOfOtherShapes_areEmpty() throws Exception {
    assertThat(StoryExtras.keyPoints(null)).isEmpty();
    assertThat(StoryExtras.keyPoints(json("null"))).isEmpty();
    assertThat(StoryExtras.keyPoints(json("7"))).isEmpty();
    assertThat(StoryExtras.keyPoints(json("{\"a\":\"b\"}"))).isEmpty();
  }

  @Test
  void keyPointsAreLimitedToTwelve_eachCutAtAWordWithAnEllipsis() throws Exception {
    List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i < 15; i++) many.add("\"p" + i + "\"");
    assertThat(StoryExtras.keyPoints(json(many.toString()))).hasSize(12).endsWith("p11");

    String long1 = ("word ".repeat(100)).trim();
    String cut = StoryExtras.keyPoints(json("[\"" + long1 + "\"]")).get(0);
    assertThat(cut).hasSizeLessThanOrEqualTo(300).endsWith("word…").doesNotContain("wor…");
  }

  @Test
  void hostileCharactersAreRemoved() throws Exception {
    String hostile = "\"a\\u0000b\\u202Ec\\u200Bd\\u2066e\\u0007f\\tg\"";
    assertThat(StoryExtras.keyPoints(json("[" + hostile + "]"))).containsExactly("abcdef g");
    assertThat(StoryExtras.analysis(json(hostile))).isEqualTo("abcdef g");
  }

  @Test
  void analysisOfOtherShapesOrBlank_isNull() throws Exception {
    assertThat(StoryExtras.analysis(null)).isNull();
    assertThat(StoryExtras.analysis(json("null"))).isNull();
    assertThat(StoryExtras.analysis(json("12"))).isNull();
    assertThat(StoryExtras.analysis(json("{\"x\":1}"))).isNull();
    assertThat(StoryExtras.analysis(json("\" \\u200B \""))).isNull();
  }

  @Test
  void aShortAnalysisIsKeptWithItsLineBreaks() throws Exception {
    assertThat(StoryExtras.analysis(json("\" One.\\nTwo. \""))).isEqualTo("One.\nTwo.");
  }

  @Test
  void aLongAnalysisIsCutAtASentenceEnd() throws Exception {
    String text = "Câu này ngắn. ".repeat(400);
    String cut = StoryExtras.analysis(json("\"" + text + "\""));
    assertThat(cut).hasSizeLessThanOrEqualTo(4000).endsWith("ngắn.");
  }

  @Test
  void aLongAnalysisWithoutSentencesIsCutAtAWord() throws Exception {
    String cut = StoryExtras.analysis(json("\"" + "từ ".repeat(2000) + "\""));
    assertThat(cut).hasSizeLessThanOrEqualTo(4000).endsWith("từ…");
  }

  @Test
  void cutWordKeepsShortTextAndCutsLongTextWithoutASpace() {
    assertThat(StoryExtras.cutWord("abc", 3)).isEqualTo("abc");
    assertThat(StoryExtras.cutWord("abcdefgh", 5)).isEqualTo("abcd…");
  }

  @Test
  void ofReturnsNothingForAStoryWithNeitherPart() throws Exception {
    assertThat(StoryExtras.of(0, "T", json("{\"title\":\"T\",\"analysis\":\"\",\"key_points\":[]}"))).isEmpty();
  }

  @Test
  void ofCarriesIndexAndCutTitle() throws Exception {
    StoryExtra e = StoryExtras.of(3, "x".repeat(400), json("{\"analysis\":\"A.\"}")).orElseThrow();
    assertThat(e.index()).isEqualTo(3);
    assertThat(e.title()).hasSize(300);
    assertThat(e.keyPoints()).isEmpty();
    assertThat(e.analysis()).isEqualTo("A.");
    assertThat(StoryExtras.of(0, null, json("{\"key_points\":[\"k\"]}")).orElseThrow().title()).isEmpty();
  }
}
