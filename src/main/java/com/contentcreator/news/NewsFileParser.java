package com.contentcreator.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds drafts from a daily news bulletin file (see README for the format): one draft per section. A section is
 * a list of stories {@code {title, summary, source, url}}, or (like {@code stocks}) an object with an
 * {@code items} list of the same. The draft's headline is the section's heading, e.g. "Tin chứng khoán (phiên
 * gần nhất 20/10)"; its summary holds one paragraph per story in the simple markup the app renders:
 * {@code **Title**: text [Source](https://link) · HH:mm dd/MM} (paragraphs separated by a blank line).
 * Each story's {@code key_points} and {@code analysis} become the draft's extras (see {@link StoryExtras}), indexed by
 * the story's position among the stories written into the summary.
 * The draft's category comes from a lookup passed in (the news_category table), so this class stays free of the database.
 */
public final class NewsFileParser {

  static final int MAX_HEADLINE = 120;
  /** The news_summary.summary column limit (ElderEase migration V41). */
  static final int MAX_SUMMARY = 8000;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, String> HEADINGS = Map.of(
      "domestic", "Tin trong nước",
      "world", "Tin thế giới",
      "stocks", "Tin chứng khoán",
      "science_tech", "Tin khoa học & công nghệ");

  private NewsFileParser() {}

  /**
   * @param json the file's text
   * @param today the day to use when the file has no valid {@code date}
   * @param categories finds an active category by code; called once per section, empty means OTHER
   * @return one draft per non-empty section, in file order
   * @throws IllegalArgumentException when the text is not a JSON object
   */
  public static List<NewsDraft> parse(String json, LocalDate today, Function<String, Optional<NewsCategory>> categories) {
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
      Written written = summary(stories);
      if (written != null) {
        String sessionDay = section.path("session_date").asText("");
        LocalDate shown = "stocks".equals(name) ? day(sessionDay, day) : day;
        Optional<NewsCategory> category = categories.apply(code(name));
        String code = category.map(NewsCategory::code).orElse(NewsDraft.OTHER);
        drafts.add(new NewsDraft(cut(heading(name, shown, category.orElse(null)), MAX_HEADLINE), written.text(), day, code, written.extras()));
      }
    });
    return drafts;
  }

  /**
   * The news_category code for a bulletin section name: upper-cased, every character that is not a letter or digit
   * (A-Z, 0-9) replaced by an underscore.
   *
   * @param name the section name, e.g. {@code science_tech}
   * @return e.g. {@code SCIENCE_TECH}
   */
  static String code(String name) {
    return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
  }

  /** The preset heading for the four known sections; else "Tin" and the category name; else a generic one. */
  private static String heading(String name, LocalDate shown, NewsCategory category) {
    String generic = category == null
        ? "Tin " + name.replace('_', ' ')
        : "Tin " + category.nameVi().toLowerCase(Locale.ROOT);
    String base = HEADINGS.getOrDefault(name, generic);
    return "stocks".equals(name) ? base + String.format(" (phiên gần nhất %02d/%02d)", shown.getDayOfMonth(), shown.getMonthValue()) : base;
  }

  /** The summary text and the extras of the stories written into it (indexed by paragraph position). */
  private record Written(String text, List<StoryExtra> extras) {}

  /** One paragraph per usable story, as many as fit the column; {@code null} when there is none. */
  private static Written summary(JsonNode stories) {
    if (!stories.isArray()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    List<StoryExtra> extras = new ArrayList<>();
    int written = 0;
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
      StoryExtras.of(written, NewsText.clean(text(story, "title")), story).ifPresent(extras::add);
      written++;
    }
    return out.isEmpty() ? null : new Written(out.toString(), extras);
  }

  /** {@code **Title**: text [Source](url) · HH:mm dd/MM}; the title, link and time are left out when missing. */
  private static String paragraph(JsonNode story) {
    String text = NewsText.clean(text(story, "summary"));
    if (text == null) {
      return null;
    }
    String title = NewsText.clean(text(story, "title"));
    String source = NewsText.clean(text(story, "source"));
    String url = text(story, "url");
    String time = story.path("published").isTextual() ? PublishedTime.format(story.get("published").asText()) : "";
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
    if (!time.isEmpty()) p.append(" · ").append(time);
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
