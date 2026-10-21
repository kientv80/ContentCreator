package com.contentcreator.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds drafts from a daily news bulletin file (see README for the format): one draft per section. A section is
 * a list of stories {@code {title, summary, source, url}}, or (like {@code stocks}) an object with an
 * {@code items} list of the same. The draft's headline is the section's heading, e.g. "📈 Tin chứng khoán (phiên
 * gần nhất 20/10)"; its summary holds one paragraph per story in the simple markup the app renders:
 * {@code **Title**: text [Source](https://link)} (paragraphs separated by a blank line).
 */
public final class NewsFileParser {

  static final int MAX_HEADLINE = 120;
  /** The news_summary.summary column limit (migration V38). */
  static final int MAX_SUMMARY = 5000;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, String> HEADINGS = Map.of(
      "domestic", "🏠 Tin trong nước",
      "world", "🌍 Tin thế giới",
      "stocks", "📈 Tin chứng khoán",
      "science_tech", "🔬 Tin khoa học & công nghệ");

  private NewsFileParser() {}

  /**
   * @param json the file's text
   * @param today the day to use when the file has no valid {@code date}
   * @return one draft per non-empty section, in file order
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
      JsonNode stories = section.isArray() ? section : section.path("items");
      String summary = summary(stories);
      if (summary != null) {
        String sessionDay = section.path("session_date").asText("");
        LocalDate shown = "stocks".equals(name) ? day(sessionDay, day) : day;
        drafts.add(new NewsDraft(cut(heading(name, shown), MAX_HEADLINE), summary, day, category(name)));
      }
    });
    return drafts;
  }

  /**
   * Maps a bulletin section name to a news_summary category code (case-insensitive).
   *
   * @param name the section name, e.g. {@code science_tech}
   * @return DOMESTIC, WORLD, STOCKS or SCIENCE_TECH; OTHER for any other name
   */
  static String category(String name) {
    return switch (name.toLowerCase(java.util.Locale.ROOT)) {
      case "domestic" -> "DOMESTIC";
      case "world" -> "WORLD";
      case "stocks" -> "STOCKS";
      case "science_tech" -> "SCIENCE_TECH";
      default -> NewsDraft.OTHER;
    };
  }

  private static String heading(String name, LocalDate shown) {
    String base = HEADINGS.getOrDefault(name, "📰 Tin " + name.replace('_', ' '));
    return "stocks".equals(name) ? base + String.format(" (phiên gần nhất %02d/%02d)", shown.getDayOfMonth(), shown.getMonthValue()) : base;
  }

  /** One paragraph per usable story, as many as fit the column; {@code null} when there is none. */
  private static String summary(JsonNode stories) {
    if (!stories.isArray()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (JsonNode story : stories) {
      String paragraph = paragraph(story);
      if (paragraph == null) {
        continue;
      }
      int added = (out.isEmpty() ? 0 : 2) + paragraph.length();
      if (out.length() + added > MAX_SUMMARY) {
        break;
      }
      if (!out.isEmpty()) out.append("\n\n");
      out.append(paragraph);
    }
    return out.isEmpty() ? null : out.toString();
  }

  /** {@code **Title**: text [Source](url)}; the title and the link are left out when missing. */
  private static String paragraph(JsonNode story) {
    String text = NewsText.clean(text(story, "summary"));
    if (text == null) {
      return null;
    }
    String title = NewsText.clean(text(story, "title"));
    String source = NewsText.clean(text(story, "source"));
    String url = text(story, "url");
    StringBuilder p = new StringBuilder();
    if (title != null) p.append("**").append(title).append("**: ");
    p.append(text);
    if (source != null) {
      p.append(' ');
      String host = url == null ? null : SafeLinks.host(url);
      if (host != null && SafeLinks.labelMatchesHost(source, host)) {
        p.append('[').append(source).append("](").append(url.replace(")", "%29")).append(')');
      } else {
        p.append("(Nguồn: ").append(source).append(')');
      }
    }
    return p.toString();
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

  private static LocalDate day(String s, LocalDate fallback) {
    try {
      return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
    } catch (DateTimeParseException e) {
      return fallback;
    }
  }
}
