package com.contentcreator.claude;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the Anthropic Messages API (see application.yml). */
@ConfigurationProperties("contentcreator.claude")
public record ClaudeProperties(
    String apiKey,
    String baseUrl,
    String model,
    int maxTokens,
    int webSearchMaxUses,
    String webSearchToolType,
    int timeoutSeconds) {

  /** Never prints the API key. */
  @Override
  public String toString() {
    return "ClaudeProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<hidden>") + ", baseUrl=" + baseUrl
        + ", model=" + model + ", maxTokens=" + maxTokens + ", webSearchMaxUses=" + webSearchMaxUses
        + ", webSearchToolType=" + webSearchToolType + ", timeoutSeconds=" + timeoutSeconds + "]";
  }
}
