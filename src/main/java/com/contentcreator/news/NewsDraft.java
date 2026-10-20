package com.contentcreator.news;

import java.time.LocalDate;

/** One AI-collected summary on its way into the ElderEase news_summary table, as an unpublished draft. */
public record NewsDraft(String headline, String summary, LocalDate newsDate) {}
