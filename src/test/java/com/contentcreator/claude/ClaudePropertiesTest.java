package com.contentcreator.claude;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClaudePropertiesTest {

  @Test
  void toStringNeverContainsTheApiKey() {
    ClaudeProperties p = new ClaudeProperties("sk-ant-secret", "https://api", "m", 10, 2, "t", 5);

    assertThat(p.toString()).doesNotContain("sk-ant-secret").contains("<hidden>", "model=m", "baseUrl=https://api");
  }

  @Test
  void toStringShowsWhenTheKeyIsNotSet() {
    assertThat(new ClaudeProperties(null, "u", "m", 1, 1, "t", 1).toString()).contains("<unset>");
    assertThat(new ClaudeProperties(" ", "u", "m", 1, 1, "t", 1).toString()).contains("<unset>");
  }
}
