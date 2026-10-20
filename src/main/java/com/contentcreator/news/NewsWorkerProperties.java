package com.contentcreator.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for {@link NewsSummaryWorker} (see application.yml). */
@ConfigurationProperties("contentcreator.news.worker")
public record NewsWorkerProperties(
    boolean enabled, String cron, String zone, boolean runOnStartup, int maxItems, int retentionDays) {}
