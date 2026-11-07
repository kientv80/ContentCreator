package com.contentcreator.news.collect;

/**
 * The single global settings row of the AI news collection ({@code news_collection_settings}, id 1).
 *
 * @param promptTemplate the admin's prompt template with its placeholders
 * @param storiesPerCategory the most stories kept per category and call
 * @param callsPerDay the most Gemini calls per Vietnam day
 */
public record CollectionSettings(String promptTemplate, int storiesPerCategory, int callsPerDay) {}
