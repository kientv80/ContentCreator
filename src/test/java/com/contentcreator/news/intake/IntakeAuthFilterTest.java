package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IntakeAuthFilterTest {

  static final String TOKEN = "fake-intake-token-0000";

  static String sha256(String text) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  private IntakeAuthFilter filter(boolean trustProxy, int perHour) throws Exception {
    NewsIntakeProperties props = new NewsIntakeProperties(sha256(TOKEN), trustProxy, 100, perHour);
    return new IntakeAuthFilter(props, new IntakeRateLimiter(Clock.systemUTC(), props));
  }

  private MockHttpServletRequest post(String authorization) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", IntakeAuthFilter.PATH);
    if (authorization != null) r.addHeader("Authorization", authorization);
    return r;
  }

  @Test
  void constructor_rejectsAHashThatIsNotSixtyFourHexCharactersWithoutEchoingIt() {
    NewsIntakeProperties props = new NewsIntakeProperties("not-a-hash-secret", false, 1, 1);

    assertThatThrownBy(() -> new IntakeAuthFilter(props, null))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not-a-hash-secret");
  }

  @Test
  void shouldNotFilter_isTrueForEveryPathExceptTheIntakePath() throws Exception {
    IntakeAuthFilter f = filter(false, 5);

    assertThat(f.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health"))).isTrue();
    assertThat(f.shouldNotFilter(post(null))).isFalse();
  }

  @Test
  void tokenMatches_acceptsTheRightTokenAndRejectsEverythingElse() throws Exception {
    IntakeAuthFilter f = filter(false, 5);

    assertThat(f.tokenMatches("Bearer " + TOKEN)).isTrue();
    assertThat(f.tokenMatches("bearer " + TOKEN)).isTrue();
    assertThat(f.tokenMatches("Bearer " + TOKEN + "x")).isFalse();
    assertThat(f.tokenMatches("Bearer ")).isFalse();
    assertThat(f.tokenMatches("Basic " + TOKEN)).isFalse();
    assertThat(f.tokenMatches(TOKEN)).isFalse();
    assertThat(f.tokenMatches(null)).isFalse();
  }

  @Test
  void clientAddress_usesTheConnectionAddressUnlessTheProxyIsTrusted() throws Exception {
    MockHttpServletRequest r = post(null);
    r.setRemoteAddr("10.0.0.5");
    r.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");

    assertThat(filter(false, 5).clientAddress(r)).isEqualTo("10.0.0.5");
    assertThat(filter(true, 5).clientAddress(r)).isEqualTo("203.0.113.9");
  }

  @Test
  void clientAddress_fallsBackWhenTheForwardedValueIsBlankAndMasksNonAddressText() throws Exception {
    MockHttpServletRequest blank = post(null);
    blank.setRemoteAddr("10.0.0.5");
    blank.addHeader("X-Forwarded-For", " ");
    MockHttpServletRequest evil = post(null);
    evil.addHeader("X-Forwarded-For", "1.1.1.1\nnews_intake_stored fake");

    assertThat(filter(true, 5).clientAddress(blank)).isEqualTo("10.0.0.5");
    assertThat(filter(true, 5).clientAddress(evil)).isEqualTo("unknown");
  }

  @Test
  void doFilterInternal_passesAValidTokenOnToTheChain() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(false, 5).doFilterInternal(post("Bearer " + TOKEN), response, chain);

    assertThat(chain.getRequest()).isNotNull();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void doFilterInternal_answers401WithAnEmptyBodyForAWrongToken() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    filter(false, 5).doFilterInternal(post("Bearer wrong"), response, chain);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString()).isEmpty();
    assertThat(chain.getRequest()).isNull();
  }

  @Test
  void doFilterInternal_answers429WithRetryAfterAndFailedAttemptsCountToo() throws Exception {
    IntakeAuthFilter f = filter(false, 2);
    for (int i = 0; i < 2; i++) f.doFilterInternal(post("Bearer wrong"), new MockHttpServletResponse(), new MockFilterChain());
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    f.doFilterInternal(post("Bearer " + TOKEN), response, chain);

    assertThat(response.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(response.getHeader("Retry-After"))).isBetween(1L, 3600L);
    assertThat(chain.getRequest()).isNull();
  }
}
