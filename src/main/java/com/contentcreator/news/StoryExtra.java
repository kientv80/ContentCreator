package com.contentcreator.news;

import java.util.List;

/**
 * The on-demand parts of one story (ElderEase table {@code news_story_extra}): its key points and AI analysis.
 *
 * @param index the story's 0-based position among the stories written into the summary text (paragraph order)
 * @param title the story title as written, at most 300 characters; empty when the story has none
 * @param keyPoints the cleaned points, at most 12, each at most 300 characters; may be empty
 * @param analysis the cleaned analysis, at most 4,000 characters; {@code null} when there is none
 */
public record StoryExtra(int index, String title, List<String> keyPoints, String analysis) {}
