package com.contentcreator.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns Claude's text answer (a JSON array, possibly wrapped in prose or a code fence) into drafts that fit
 * the news_summary columns: headline at most 120 and summary at most 1,000 characters.
 */
public final class NewsDraftParser {

  static final int MAX_HEADLINE = 120;
  static final int MAX_SUMMARY = 1000;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private NewsDraftParser() {}

  /**
   * @param text the model's answer
   * @param today the Vietnam day used when a story has no usable date
   * @return the valid drafts; entries without a headline or summary are dropped
   * @throws IllegalArgumentException when the text holds no JSON array
   */
  public static List<NewsDraft> parse(String text, LocalDate today) {
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
    List<NewsDraft> drafts = new ArrayList<>();
    for (JsonNode node : root) {
      String headline = blankToNull(NewsText.clean(text(node, "headline")));
      String summary = blankToNull(NewsText.clean(text(node, "summary")));
      if (headline == null || summary == null) {
        continue;
      }
      drafts.add(new NewsDraft(cut(headline, MAX_HEADLINE), cut(summary, MAX_SUMMARY), day(text(node, "newsDate"), today), NewsDraft.OTHER));
    }
    return drafts;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    String s = value.asText().trim();
    return s.isEmpty() ? null : s;
  }

  private static String blankToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static LocalDate day(String s, LocalDate today) {
    if (s == null) {
      return today;
    }
    try {
      return Instant.parse(s).atZone(VIETNAM).toLocalDate();
    } catch (DateTimeParseException ignored) {
      // maybe a plain date
    }
    try {
      return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
    } catch (DateTimeParseException e) {
      return today;
    }
  }
}
