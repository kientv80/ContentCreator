package com.contentcreator.thoughts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for {@link ShareThoughtWorker} (see application.yml).
 *
 * @param enabled whether the scheduled scan runs
 * @param directory the folder watched for letters
 * @param pollSeconds seconds between scans
 * @param settleSeconds a file must be unmodified this long before it is read
 * @param maxBytes the largest letter file accepted
 * @param accounts the system account (username) that posts the letters of each language
 */
@ConfigurationProperties("contentcreator.thoughts")
public record ThoughtProperties(boolean enabled, String directory, long pollSeconds, long settleSeconds, long maxBytes, Accounts accounts) {

  /**
   * The usernames of the system accounts in ElderEase's users table.
   *
   * @param vi the account for {@code -vi} letters
   * @param en the account for {@code -en} letters
   */
  public record Accounts(String vi, String en) {}
}
