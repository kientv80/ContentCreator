package com.contentcreator.news.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the news intake API (see application.yml). The intake route exists only when {@code tokenSha256} is not blank.
 *
 * @param tokenSha256 SHA-256 of the intake token as 64 hex characters; blank disables the endpoint. The token itself is never stored here
 * @param trustProxy true behind Caddy: the client address is the right-most {@code X-Forwarded-For} value
 * @param maxBytes largest accepted request body
 * @param perHour requests allowed per client address and hour
 */
@ConfigurationProperties("contentcreator.news.intake")
public record NewsIntakeProperties(
    @DefaultValue("") String tokenSha256,
    @DefaultValue("false") boolean trustProxy,
    @DefaultValue("2000000") int maxBytes,
    @DefaultValue("30") int perHour) {

  /** @return the record without the hash, so no log line or exception text can leak it */
  @Override
  public String toString() {
    return "NewsIntakeProperties[tokenSha256=" + (tokenSha256 == null || tokenSha256.isBlank() ? "<unset>" : "<hidden>")
        + ", trustProxy=" + trustProxy + ", maxBytes=" + maxBytes + ", perHour=" + perHour + "]";
  }
}
