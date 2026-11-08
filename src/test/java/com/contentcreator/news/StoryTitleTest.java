package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoryTitleTest {

  @Test
  void normalize_lowercasesRemovesAccentsAndMapsDStroke() {
    assertThat(StoryTitle.normalize("Đường Hà Nội, ÉCOLE")).isEqualTo("duong ha noi ecole");
  }

  @Test
  void normalize_turnsPunctuationAndRepeatedSpacesIntoOneSpaceAndTrims() {
    assertThat(StoryTitle.normalize("  Hello,   world!!  (2026) - \"ok\" ")).isEqualTo("hello world 2026 ok");
  }

  @Test
  void normalize_returnsEmptyForNullOrNoAlphanumerics() {
    assertThat(StoryTitle.normalize(null)).isEmpty();
    assertThat(StoryTitle.normalize(" -- !! ")).isEmpty();
  }

  @Test
  void hash_isEqualForVariantsDifferentForOtherTitlesAndNullWhenEmpty() {
    assertThat(StoryTitle.hash("Giá vàng hôm nay")).isEqualTo(StoryTitle.hash("GIA VANG, hom nay"));
    assertThat(StoryTitle.hash("Giá vàng hôm nay")).isNotEqualTo(StoryTitle.hash("Giá bạc hôm nay")).hasSize(64);
    assertThat(StoryTitle.hash("!!")).isNull();
    assertThat(StoryTitle.hash(null)).isNull();
  }
}
