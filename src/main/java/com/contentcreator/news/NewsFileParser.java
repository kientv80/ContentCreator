package com.contentcreator.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds drafts from a daily news bulletin file (see README for the format): a {@code date} and
 * {@code sections}, each a list of stories {@code {title, summary, source, url}}. The {@code stocks} section
 * (an object with an {@code items} list) becomes one market-summary draft. Every draft fits the news_summary
 * columns: headline at most 120 characters, summary at most 1,000 including the "(Nguồn: ...)" ending.
 */
public final class NewsFileParser {

  static final int MAX_HEADLINE = 120;
  static final int MAX_SUMMARY = 1000;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private NewsFileParser() {}

  /**
   * @param json the file's text
   * @param today the day to use when the file has no valid {@code date}
   * @return the drafts, possibly empty
   * @throws IllegalArgumentException when the text is not a JSON object
   */
  public static List<NewsDraft> parse(String json, LocalDate today) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("Not valid JSON", e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("Not a JSON object");
    }
    LocalDate day = day(root.path("date").asText(""), today);
    List<NewsDraft> drafts = new ArrayList<>();
    JsonNode sections = root.path("sections");
    sections.fieldNames().forEachRemaining(name -> {
      JsonNode section = sections.get(name);
      if (section.isArray()) {
        for (JsonNode story : section) {
          NewsDraft draft = story(story, day);
          if (draft != null) drafts.add(draft);
        }
      } else if (section.isObject() && section.path("items").isArray()) {
        NewsDraft draft = market(section, day);
        if (draft != null) drafts.add(draft);
      }
    });
    return drafts;
  }

  private static NewsDraft story(JsonNode node, LocalDate day) {
    String title = text(node, "title");
    String summary = text(node, "summary");
    if (title == null || summary == null) {
      return null;
    }
    return new NewsDraft(cut(title, MAX_HEADLINE), withSources(summary, text(node, "source")), day);
  }

  /** One draft for the stock-market section: the index line as the headline, the items' texts as the summary. */
  private static NewsDraft market(JsonNode section, LocalDate day) {
    StringBuilder body = new StringBuilder();
    Set<String> sources = new LinkedHashSet<>();
    for (JsonNode item : section.get("items")) {
      String summary = text(item, "summary");
      if (summary == null) {
        continue;
      }
      if (!body.isEmpty()) body.append(' ');
      body.append(summary);
      String source = text(item, "source");
      if (source != null) sources.add(source);
    }
    if (body.isEmpty()) {
      return null;
    }
    String headline = "Chứng khoán ngày " + String.format("%02d/%02d", day.getDayOfMonth(), day.getMonthValue());
    JsonNode first = section.path("indices").path(0);
    if (first.hasNonNull("name") && first.hasNonNull("close")) {
      headline += ": " + first.get("name").asText() + " " + String.format(java.util.Locale.forLanguageTag("vi"), "%,.2f", first.get("close").asDouble()) + " điểm";
    }
    return new NewsDraft(cut(headline, MAX_HEADLINE), withSources(body.toString(), sources.isEmpty() ? null : String.join(", ", sources)), day);
  }

  /** Appends "(Nguồn: X)", cutting the body (with an ellipsis) so the whole text stays within the column. */
  static String withSources(String body, String source) {
    String suffix = source == null ? "" : " (Nguồn: " + source + ")";
    int room = MAX_SUMMARY - suffix.length();
    String text = body.length() <= room ? body : body.substring(0, Math.max(0, room - 1)).stripTrailing() + "…";
    return text + suffix;
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
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static LocalDate day(String s, LocalDate today) {
    try {
      return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
    } catch (DateTimeParseException e) {
      return today;
    }
  }
}
