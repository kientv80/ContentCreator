package com.contentcreator.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SafeLinksTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "https://vnexpress.net/a-b-1.html", "http://cafef.vn/x", "HTTPS://VnExpress.NET/x", "https://www.vnexpress.net",
      "https://vnexpress.net:8443/x?q=1#f", "https://a.b-c.vn?x=1", "https://vnexpress.net/x_(1)", "https://vnexpress.net/đường"})
  void safeUrlsAreAccepted(String url) {
    assertThat(SafeLinks.isSafe(url)).as(url).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      "https://vnexpress.net@evil.example", "https://vnexpress.net@evil.example/x", "https://user:pw@vnexpress.net/",
      "https://xn--vnexprss-x9a.net/a", "https://news.XN--abc.net/a", "https://vnexprèss.net/a", "https://vnехpress.net/a",
      "http://169.254.169.254/", "http://127.0.0.1:8080/", "http://2130706433/", "http://[::1]/x", "http://[2001:db8::1]:80/",
      "http://localhost/x", "https://vnexpress/x", "https://.vnexpress.net/", "https://vnexpress.net./", "https://-a.net/",
      "https://a..net/", "https://vn_express.net/", "https:///x", "https://", "http:evil.com", "//evil.com",
      "javascript:alert(1)", "intent://x#Intent;scheme=http;end", "file:///etc/passwd", "data:text/html,hi", "ftp://a.vn/x",
      "https://vnexpress.net/a b", "https://vnexpress.net/\u0000", "https://vnexpress.net/\n", "https://vn express.net/",
      "https://vnexpress.net/‮exe.txt", "https://vnexpress.net‮/x", "https://vnexpress.​net/x",
      "https://vnexpress.net/ x", "https://vnexpress.net\\@evil.example", "https://vnexpress.net/\\x",
      "https://vnexpress.net/[x]", "https://vnexpress.net/*", "https://vnexpress.net/<", "https://vnexpress.net/\"",
      "https://vnexpress.net:/x", "https://vnexpress.net:80a/x", "https://vnexpress.net:1:2/x", "https://vnexpress.net:123456/x",
      "https://vnexpress.net/", "https://vnexpress.net/ ", "https://vnexpress.net/\uD800"})
  void unsafeUrlsAreRejected(String url) {
    assertThat(SafeLinks.isSafe(url)).as(url).isFalse();
  }

  @Test
  void lengthLimitIs500() {
    String base = "https://vnexpress.net/";
    assertThat(SafeLinks.isSafe(base + "a".repeat(500 - base.length()))).isTrue();
    assertThat(SafeLinks.isSafe(base + "a".repeat(501 - base.length()))).isFalse();
  }

  @Test
  void hostIsLowerCasedWithoutPort_orNullWhenUnsafe() {
    assertThat(SafeLinks.host("https://WWW.VnExpress.net:8443/x")).isEqualTo("www.vnexpress.net");
    assertThat(SafeLinks.host("https://a@b.net")).isNull();
  }

  @Test
  void aDomainLikeLabelMustBeTheHostOrAParentDomain() {
    assertThat(SafeLinks.labelMatchesHost("vnexpress.net", "vnexpress.net")).isTrue();
    assertThat(SafeLinks.labelMatchesHost("VnExpress.net", "www.vnexpress.net")).isTrue();
    assertThat(SafeLinks.labelMatchesHost("www.vnexpress.net", "vnexpress.net")).isTrue();
    assertThat(SafeLinks.labelMatchesHost("vnexpress.net", "e.vnexpress.net")).isTrue();
    assertThat(SafeLinks.labelMatchesHost("vnexpress.net", "evilvnexpress.net")).isFalse();
    assertThat(SafeLinks.labelMatchesHost("vnexpress.net", "vnexpress.net.evil.example")).isFalse();
    assertThat(SafeLinks.labelMatchesHost("vnexpress.net", "evil.example")).isFalse();
    assertThat(SafeLinks.labelMatchesHost("e.vnexpress.net", "vnexpress.net")).isFalse();
  }

  @Test
  void aLabelThatIsNotADomainAlwaysMatches() {
    assertThat(SafeLinks.labelMatchesHost("VnExpress", "evil.example")).isTrue();
    assertThat(SafeLinks.labelMatchesHost("Báo vnexpress.net", "evil.example")).isTrue();
  }
}
