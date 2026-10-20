package com.contentcreator.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for {@link NewsFileWorker} (see application.yml). */
@ConfigurationProperties("contentcreator.news.file-worker")
public record NewsFileProperties(boolean enabled, String directory, long pollSeconds, long settleSeconds, long maxBytes) {}
