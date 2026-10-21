package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublishedTimeTest {

  @Test
  void aVietnamOffsetKeepsItsClock() {
    assertThat(PublishedTime.format("2026-10-20T15:47:30+07:00")).isEqualTo("15:47 20/10");
    assertThat(PublishedTime.format("2026-10-21T00:05:00+07:00")).isEqualTo("00:05 21/10");
    assertThat(PublishedTime.format("2026-10-20T14:11+07:00")).isEqualTo("14:11 20/10");
    assertThat(PublishedTime.format("2026-10-20T14:11+0700")).isEqualTo("14:11 20/10");
  }

  @Test
  void otherOffsetsAreConvertedToVietnamTime_includingIntoTheNextDay() {
    assertThat(PublishedTime.format("2026-10-20T08:47:30Z")).isEqualTo("15:47 20/10");
    assertThat(PublishedTime.format("2026-10-20T23:11Z")).isEqualTo("06:11 21/10");
    assertThat(PublishedTime.format("2026-10-20T10:00-05:00")).isEqualTo("22:00 20/10");
  }

  @Test
  void noOffsetIsReadAsVietnamTime_andTextAfterTheDateIsIgnored() {
    assertThat(PublishedTime.format("2026-10-20T22:20:13 (Al Jazeera meta)")).isEqualTo("22:20 20/10");
    assertThat(PublishedTime.format("2026-10-20 15:19 (Al Jazeera meta; cập nhật 16:34)")).isEqualTo("15:19 20/10");
    assertThat(PublishedTime.format("  2026-10-20T15:31Z (meta); cập nhật 2026-10-21 12:59")).isEqualTo("22:31 20/10");
  }

  @Test
  void aDateOnlyOrAMidnightPlaceholderShowsOnlyTheDate() {
    assertThat(PublishedTime.format("2026-10-21")).isEqualTo("21/10");
    assertThat(PublishedTime.format("2026-10-21T00:00+07:00")).isEqualTo("21/10");
    assertThat(PublishedTime.format("2026-10-21T00:00:00")).isEqualTo("21/10");
    assertThat(PublishedTime.format("2026-10-20T17:00:00Z")).isEqualTo("21/10");
    assertThat(PublishedTime.format("2026-10-21T00:00:30")).isEqualTo("00:00 21/10");
    assertThat(PublishedTime.format("2026-10-20 2:53 pm (Algemeiner)")).isEqualTo("20/10");
  }

  @Test
  void missingOrUnreadableValuesGiveNoTime() {
    assertThat(PublishedTime.format(null)).isEmpty();
    assertThat(PublishedTime.format("")).isEmpty();
    assertThat(PublishedTime.format("   ")).isEmpty();
    assertThat(PublishedTime.format("soon")).isEmpty();
    assertThat(PublishedTime.format("2026-13-01")).isEmpty();
    assertThat(PublishedTime.format("2026-02-30T10:00")).isEmpty();
    assertThat(PublishedTime.format("2026-10-20T25:00")).isEmpty();
    assertThat(PublishedTime.format("2026-10-20T10:61")).isEmpty();
    assertThat(PublishedTime.format("2026-10-20T10:00+99:00")).isEmpty();
  }
}
