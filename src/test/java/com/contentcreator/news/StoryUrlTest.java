package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoryUrlTest {

  @Test
  void normalize_lowercasesTheHostAndDropsPortFragmentAndTrailingSlashesButKeepsOtherParameters() {
    assertThat(StoryUrl.normalize("https://EXAMPLE.test:443/A/b//#frag")).isEqualTo("https://example.test/A/b");
    assertThat(StoryUrl.normalize("https://example.test/")).isEqualTo("https://example.test");
    assertThat(StoryUrl.normalize("https://example.test/a?id=7&x=1")).isEqualTo("https://example.test/a?id=7&x=1");
  }

  @Test
  void normalize_stripsEveryTrackingParameterKeepingTheRestInOrder() {
    String url = "https://example.test/a?utm_source=x&id=7&UTM_Medium=y&fbclid=1&gclid=2&mc_cid=3&mc_eid=4&ref=5&ref_src=6&igshid=7&z=9&flag";

    assertThat(StoryUrl.normalize(url)).isEqualTo("https://example.test/a?id=7&z=9&flag");
  }

  @Test
  void normalize_dropsTheQuestionMarkWhenOnlyTrackingParametersRemain() {
    assertThat(StoryUrl.normalize("https://example.test/a?utm_source=x&fbclid=1")).isEqualTo("https://example.test/a");
    assertThat(StoryUrl.normalize("https://example.test/a?utm_source=x")).isEqualTo(StoryUrl.normalize("https://example.test/a"));
  }

  @Test
  void normalize_keepsParametersThatMerelyStartLikeATrackingName() {
    assertThat(StoryUrl.normalize("https://example.test/a?reference=1&utm=2&referrer=3")).isEqualTo("https://example.test/a?reference=1&utm=2&referrer=3");
  }

  @Test
  void cleanQuery_handlesNullEmptyAndEmptyPairs() {
    assertThat(StoryUrl.cleanQuery(null)).isEmpty();
    assertThat(StoryUrl.cleanQuery("")).isEmpty();
    assertThat(StoryUrl.cleanQuery("a=1&&b=2&")).isEqualTo("a=1&b=2");
  }

  @Test
  void hash_isTheSha256HexOfTheText() {
    assertThat(StoryUrl.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
