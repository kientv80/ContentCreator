package com.contentcreator.news;

import java.time.Instant;

/** One collected story, before it is stored. */
public record NewsItem(
    String title, String summary, String category, String sourceName, String sourceUrl, Instant publishedAt) {}
