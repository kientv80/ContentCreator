package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NewsIntakePropertiesTest {

  private static final String HASH = "a".repeat(64);

  @Test
  void toStringHidesTheHash() {
    String text = new NewsIntakeProperties(HASH, true, 5, 7).toString();

    assertThat(text).doesNotContain(HASH).contains("<hidden>", "trustProxy=true", "maxBytes=5", "perHour=7");
  }

  @Test
  void toStringMarksAnUnsetHash() {
    assertThat(new NewsIntakeProperties("", false, 1, 1).toString()).contains("<unset>");
    assertThat(new NewsIntakeProperties(null, false, 1, 1).toString()).contains("<unset>");
  }
}
