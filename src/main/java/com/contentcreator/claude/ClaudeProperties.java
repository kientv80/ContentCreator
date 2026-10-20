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
    int timeoutSeconds) {}
