package com.contentcreator.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Turns Claude's text answer (a JSON array, possibly wrapped in prose or a code fence) into items. */
public final class NewsJsonParser {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private NewsJsonParser() {}

  /**
   * @param text the model's answer
   * @return the valid items; entries without a title, summary or http(s) link are dropped
   * @throws IllegalArgumentException when the text holds no JSON array
   */
  public static List<NewsItem> parse(String text) {
    int start = text == null ? -1 : text.indexOf('[');
    int end = text == null ? -1 : text.lastIndexOf(']');
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("No JSON array in the answer");
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(text.substring(start, end + 1));
    } catch (Exception e) {
      throw new IllegalArgumentException("The answer is not valid JSON", e);
    }
    List<NewsItem> items = new ArrayList<>();
    for (JsonNode node : root) {
      String title = text(node, "title");
      String summary = text(node, "summary");
      String url = text(node, "url");
      if (title == null || summary == null || !isHttpUrl(url)) {
        continue;
      }
      items.add(new NewsItem(cut(title, 300), summary, cut(text(node, "category"), 60), cut(text(node, "source"), 120), cut(url, 1000), date(text(node, "published"))));
    }
    return items;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String s = value.asText().trim();
    return s.isEmpty() ? null : s;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }

  private static boolean isHttpUrl(String url) {
    if (url == null) {
      return false;
    }
    try {
      String scheme = URI.create(url).getScheme();
      return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Parses an ISO instant or a plain date (taken as 00:00 UTC); {@code null} when absent or unreadable. */
  public static Instant date(String s) {
    if (s == null) {
      return null;
    }
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException ignored) {
      // fall through to a plain date
    }
    try {
      return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay().toInstant(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
