package com.contentcreator.news.intake;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Tests of the address allow-list in {@link IntakeAuthFilter}: callers from an allowed range pass without a token. */
class IntakeAddressAllowTest {

  private static IntakeAuthFilter filter(String cidrs) {
    NewsIntakeProperties props = new NewsIntakeProperties("", true, 2_000_000, 30, cidrs);
    return new IntakeAuthFilter(props, new IntakeRateLimiter(java.time.Clock.systemUTC(), props));
  }

  @Test
  void anAddressInsideTheRangePassesWithoutAToken() {
    IntakeAuthFilter filter = filter("160.79.104.0/21");
    assertTrue(filter.allowedByAddress("160.79.106.130"));
    assertFalse(filter.tokenMatches(null));
  }

  @Test
  void anAddressOutsideTheRangeIsNotAllowedAndNoTokenIsConfigured() {
    IntakeAuthFilter filter = filter("160.79.104.0/21");
    assertFalse(filter.allowedByAddress("202.83.2.20"));
    assertFalse(filter.allowedByAddress("unknown"));
  }

  @Test
  void withNoRangeNothingIsAllowedByAddress() {
    IntakeAuthFilter filter = new IntakeAuthFilter(
        new NewsIntakeProperties("0000000000000000000000000000000000000000000000000000000000000000", true, 2_000_000, 30),
        new IntakeRateLimiter(java.time.Clock.systemUTC(), new NewsIntakeProperties("", true, 2_000_000, 30)));
    assertFalse(filter.allowedByAddress("160.79.106.130"));
  }

  private static MockHttpServletRequest post(String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", IntakeAuthFilter.PATH);
    request.setRequestURI(IntakeAuthFilter.PATH);
    request.addHeader("X-Forwarded-For", forwardedFor);
    return request;
  }

  @Test
  void theFilterLetsAnAllowedCallerThroughAndRefusesAnOtherWith401() throws Exception {
    IntakeAuthFilter filter = filter("160.79.104.0/21");

    MockHttpServletResponse allowed = new MockHttpServletResponse();
    MockFilterChain allowedChain = new MockFilterChain();
    filter.doFilter(post("160.79.106.130"), allowed, allowedChain);
    assertTrue(allowedChain.getRequest() != null);
    assertTrue(allowed.getStatus() == 200);

    MockHttpServletResponse refused = new MockHttpServletResponse();
    MockFilterChain refusedChain = new MockFilterChain();
    filter.doFilter(post("198.51.100.9"), refused, refusedChain);
    assertTrue(refusedChain.getRequest() == null);
    assertTrue(refused.getStatus() == 401);
  }

  @Test
  void aForgedLeftmostForwardedAddressDoesNotCount() throws Exception {
    IntakeAuthFilter filter = filter("160.79.104.0/21");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(post("160.79.106.130, 198.51.100.9"), response, chain);
    assertTrue(chain.getRequest() == null);
    assertTrue(response.getStatus() == 401);
  }
}
