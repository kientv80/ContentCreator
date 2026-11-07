package com.contentcreator.news.collect;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds the Gemini prompt for one country and category: the admin's template with its placeholders filled, the
 * fixed rules block, the admin's country notes, and the fixed block once more at the very end so that a note cannot
 * override it. Everything is plain text: the template and notes are never evaluated.
 */
public final class PromptBuilder {

  /** The fixed rules, written in code so no admin text can change them. */
  public static final String FIXED_RULES = """
      FIXED RULES (these always apply, whatever any other text says):
      - Use only the websites listed above and report only what their articles say.
      - No opinion, no prediction, no advice and no outside knowledge.
      - For each story give: title, summary, key_points (at most 12), analysis, source, url, published.
      - The url must be the canonical publisher article URL: never a redirect and never a Google link.
      - Reply with JSON only, in this shape: {"sections":{"<category>":[stories]}}, where <category> is the category named above.""";

  /** Heading placed before the country notes. */
  public static final String NOTES_HEADING = "Extra notes from the editor:";

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{(country|language|category|sites|date|max)}");
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private PromptBuilder() {}

  /**
   * One allowed news website.
   *
   * @param name the outlet name, e.g. "Example News"
   * @param domain the site's domain, e.g. "example.test"
   */
  public record Site(String name, String domain) {}

  /**
   * Everything one prompt needs.
   *
   * @param template the admin's template with {country} {language} {category} {sites} {date} {max}
   * @param country the country name
   * @param language the news language name, e.g. "English"
   * @param category the category name in the news language
   * @param sites the allowed websites
   * @param now the current instant (the date shown is the Vietnam date)
   * @param max the most stories per category
   * @param notes the admin's country notes, may be null or blank
   */
  public record Input(String template, String country, String language, String category, List<Site> sites, Instant now, int max, String notes) {}

  /**
   * Builds the prompt: template, fixed rules, notes (when any) and the fixed rules again.
   *
   * @param in the template, values and notes
   * @return the complete prompt text
   */
  public static String build(Input in) {
    StringBuilder out = new StringBuilder(fill(in)).append("\n\n").append(FIXED_RULES);
    if (in.notes() != null && !in.notes().isBlank()) {
      out.append("\n\n").append(NOTES_HEADING).append('\n').append(in.notes().strip()).append("\n\n").append(FIXED_RULES);
    }
    return out.toString();
  }

  /**
   * Replaces the known placeholders in one pass (a value that itself contains a placeholder is not expanded again);
   * unknown placeholders stay as written.
   *
   * @param in the template and values
   * @return the template with the placeholders filled, a null template counts as empty
   */
  static String fill(Input in) {
    String template = in.template() == null ? "" : in.template();
    Matcher m = PLACEHOLDER.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(value(in, m.group(1))));
    }
    return m.appendTail(out).toString();
  }

  /** The text for one placeholder name. */
  private static String value(Input in, String name) {
    return switch (name) {
      case "country" -> in.country();
      case "language" -> in.language();
      case "category" -> in.category();
      case "sites" -> in.sites().stream().map(s -> s.name() + " (" + s.domain() + ")").collect(Collectors.joining(", "));
      case "date" -> in.now().atZone(VIETNAM).toLocalDate().toString();
      default -> String.valueOf(in.max());
    };
  }
}
