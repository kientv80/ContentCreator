package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link IntakeAuthFilter#rejectReason(String)}: the log reason never contains the token. */
class IntakeRejectReasonTest {

  @Test
  void rejectReason_namesAMissingHeader() {
    assertThat(IntakeAuthFilter.rejectReason(null)).isEqualTo("no_header");
    assertThat(IntakeAuthFilter.rejectReason("  ")).isEqualTo("no_header");
  }

  @Test
  void rejectReason_namesAHeaderThatIsNotBearer() {
    assertThat(IntakeAuthFilter.rejectReason("Basic abc")).isEqualTo("not_bearer");
  }

  @Test
  void rejectReason_namesAnEmptyBearerValue() {
    assertThat(IntakeAuthFilter.rejectReason("Bearer   ")).isEqualTo("empty_token");
  }

  @Test
  void rejectReason_givesOnlyTheLengthOfAWrongToken() {
    String reason = IntakeAuthFilter.rejectReason("Bearer secret-value-123");
    assertThat(reason).isEqualTo("wrong_token len=16").doesNotContain("secret");
  }
}
