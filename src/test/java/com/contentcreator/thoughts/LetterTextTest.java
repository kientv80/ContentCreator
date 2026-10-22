package com.contentcreator.thoughts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LetterTextTest {

  @Test
  void nullGivesEmpty() {
    assertThat(LetterText.toPlainText(null)).isEmpty();
  }

  @Test
  void stripsBomNormalisesNewlinesAndDropsRules() {
    assertThat(LetterText.toPlainText("﻿A\r\n---\r\nB\rC")).isEqualTo("A\n\nB\nC");
  }

  @Test
  void headingsBecomePlainLinesAndEmphasisIsRemoved() {
    assertThat(LetterText.toPlainText("# Title\n## Bài 1: **bold** and *it* `code`")).isEqualTo("Title\nBài 1: bold and it code");
  }

  @Test
  void bulletsBecomeDots() {
    assertThat(LetterText.toPlainText("- one\n* two\n  - three\n*bold* x")).isEqualTo("• one\n• two\n• three\nbold x");
  }

  @Test
  void linksKeepOnlyTheirText() {
    assertThat(LetterText.toPlainText("see [the page](https://x.test/a) now")).isEqualTo("see the page now");
  }

  @Test
  void collapsesBlankLinesAndTrims() {
    assertThat(LetterText.toPlainText("\n\nA  \n\n\n\n\nB\n\n")).isEqualTo("A\n\nB");
  }

  @Test
  void stripsControlAndBidiCharactersButKeepsTabsAndEmojiJoiners() {
    assertThat(LetterText.toPlainText("a\u0007b‮c​d\te⁦f👨‍👩")).isEqualTo("abcd\tef👨‍👩");
  }

  @Test
  void aLongLetterIsCutAfterTheLastWholeParagraphThatFits() {
    String p = "x".repeat(2500);
    String result = LetterText.toPlainText(p + "\n\n" + p + "\n\n" + p);
    assertThat(result).isEqualTo(p + "\n\n" + p);
  }

  @Test
  void aLongLetterWithoutBlankLinesAndALongSingleParagraphGiveEmpty() {
    assertThat(LetterText.toPlainText("y".repeat(6001))).isEmpty();
    assertThat(LetterText.toPlainText("short\n\n" + "y".repeat(6001))).isEqualTo("short");
  }

  @Test
  void exactlyTheLimitIsKept() {
    assertThat(LetterText.toPlainText("z".repeat(6000))).hasSize(6000);
  }

  @Test
  void realLettersFitInOnePostAndKeepTheirStructure() throws IOException {
    for (String name : new String[] {"2026-10-22-vi.md", "2026-10-22-en.md"}) {
      String text = LetterText.toPlainText(Files.readString(Path.of("src/test/resources/letters", name)));
      assertThat(text).isNotEmpty().hasSizeLessThanOrEqualTo(6000).doesNotContain("**", "`", "\n\n\n", "---");
      assertThat(text).doesNotStartWith("#");
    }
  }
}
