package com.contentcreator.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the Google Gemini API (see application.yml). */
@ConfigurationProperties("contentcreator.gemini")
public record GeminiProperties(String apiKey, String baseUrl, String model, int maxTokens, int timeoutSeconds) {

  /** Never prints the API key. */
  @Override
  public String toString() {
    return "GeminiProperties[apiKey=" + (apiKey == null || apiKey.isBlank() ? "<unset>" : "<hidden>") + ", baseUrl=" + baseUrl
        + ", model=" + model + ", maxTokens=" + maxTokens + ", timeoutSeconds=" + timeoutSeconds + "]";
  }
}
