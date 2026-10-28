package com.contentcreator.news;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reads and cleans a bulletin story's {@code key_points} and {@code analysis} (ElderEase design 5.36). */
final class StoryExtras {

  static final int MAX_POINTS = 12;
  static final int MAX_POINT = 300;
  static final int MAX_ANALYSIS = 4000;
  static final int MAX_TITLE = 300;
  private static final Pattern CONTROL = Pattern.compile("[[\\p{Cc}&&[^\\n]]\\u200B-\\u200D\\u2060\\uFEFF\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]");
  private static final Pattern LINES = Pattern.compile("\\r\\n|\\r|\\n");

  private StoryExtras() {}

  /**
   * @param index the story's position among the written stories
   * @param title the cleaned story title, or {@code null}
   * @param story the bulletin story object
   * @return the extra, empty when the story has neither key points nor analysis
   */
  static Optional<StoryExtra> of(int index, String title, JsonNode story) {
    List<String> points = keyPoints(story.get("key_points"));
    String analysis = analysis(story.get("analysis"));
    if (points.isEmpty() && analysis == null) {
      return Optional.empty();
    }
    return Optional.of(new StoryExtra(index, cutWord(title == null ? "" : title, MAX_TITLE), points, analysis));
  }

  /**
   * @param node an array of strings, one string with line breaks, or anything else (missing, null, number, object)
   * @return up to 12 non-blank points of at most 300 characters; empty for other shapes
   */
  static List<String> keyPoints(JsonNode node) {
    List<String> raw = new ArrayList<>();
    if (node != null && node.isArray()) {
      node.forEach(n -> {
        if (n.isTextual()) raw.add(n.asText());
      });
    } else if (node != null && node.isTextual()) {
      raw.add(node.asText());
    }
    List<String> points = new ArrayList<>();
    for (String r : raw) {
      for (String line : LINES.split(r)) {
        String point = strip(line);
        if (!point.isEmpty() && points.size() < MAX_POINTS) {
          points.add(cutWord(point, MAX_POINT));
        }
      }
    }
    return List.copyOf(points);
  }

  /**
   * @param node a string, or anything else (missing, null, number, object)
   * @return the cleaned text of at most 4,000 characters, cut at a sentence end (else a word); {@code null} when not text or blank
   */
  static String analysis(JsonNode node) {
    if (node == null || !node.isTextual()) {
      return null;
    }
    String s = strip(node.asText());
    if (s.isEmpty()) {
      return null;
    }
    if (s.length() <= MAX_ANALYSIS) {
      return s;
    }
    String head = s.substring(0, MAX_ANALYSIS);
    int end = -1;
    for (int i = head.length() - 1; i >= MAX_ANALYSIS / 2 && end < 0; i--) {
      char c = head.charAt(i);
      boolean last = i + 1 == head.length();
      if ((c == '.' || c == '!' || c == '?') && (last || Character.isWhitespace(head.charAt(i + 1)))) {
        end = i + 1;
      }
    }
    return end > 0 ? head.substring(0, end).trim() : cutWord(s, MAX_ANALYSIS);
  }

  /** Removes control, zero-width and bidi characters (keeping line breaks), and trims. Tabs become spaces. */
  static String strip(String s) {
    return CONTROL.matcher(s.replace('\t', ' ')).replaceAll("").trim();
  }

  /** At most {@code max} characters: longer text is cut at the last space and ends with "…". */
  static String cutWord(String s, int max) {
    if (s.length() <= max) {
      return s;
    }
    String head = s.substring(0, max - 1);
    int space = head.lastIndexOf(' ');
    if (space > 0) {
      head = head.substring(0, space);
    }
    return head.stripTrailing() + "…";
  }
}
