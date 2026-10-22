package com.contentcreator.thoughts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns a Markdown letter into the plain text of its Shared Thought posts. */
public final class LetterText {

  /** The longest text a post may have (ElderEase poems.content_text, migration V43). */
  public static final int MAX_CHARS = 6000;

  private static final Logger log = LoggerFactory.getLogger(LetterText.class);
  private static final Pattern CONTROL = Pattern.compile("[[\\p{Cc}&&[^\\n\\t]]\\u200B\\uFEFF\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069]");
  private static final Pattern RULE = Pattern.compile("^\\s*-{3,}\\s*$");
  private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
  private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");

  private LetterText() {}

  /**
   * Converts a Markdown letter to plain text: strips a BOM, control and bidi characters, drops {@code ---} rule
   * lines, keeps headings as plain lines, removes {@code **}, {@code *} and backticks, turns {@code - } / {@code * }
   * bullets into {@code • }, keeps only the text of {@code [text](url)} links and collapses runs of blank lines.
   * A result longer than {@value #MAX_CHARS} characters is cut after the last whole paragraph that fits.
   *
   * @param markdown the letter, or {@code null}
   * @return the plain text, at most {@value #MAX_CHARS} characters; empty when nothing usable is left (including
   *     when even the first paragraph is too long)
   */
  public static String toPlainText(String markdown) {
    if (markdown == null) {
      return "";
    }
    String text = CONTROL.matcher(markdown.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
    StringBuilder out = new StringBuilder();
    for (String line : text.split("\n", -1)) {
      if (RULE.matcher(line).matches()) {
        out.append('\n');
        continue;
      }
      String l = HEADING.matcher(line).replaceFirst("");
      l = BULLET.matcher(l).replaceFirst("• ");
      l = LINK.matcher(l).replaceAll("$1");
      l = l.replace("**", "").replace("*", "").replace("`", "");
      out.append(l.stripTrailing()).append('\n');
    }
    String plain = out.toString().replaceAll("\\n{3,}", "\n\n").strip();
    return plain.length() <= MAX_CHARS ? plain : cutAtParagraph(plain);
  }

  /**
   * Splits a letter into its posts: one per line starting with {@code "## "} (its heading line without the
   * {@code ## }, a blank line, then its body), each converted with {@link #toPlainText(String)}. Text before the
   * first {@code ## } (the letter's {@code # } title and rules) is dropped. Sections without a body are dropped, and
   * a post longer than {@value #MAX_CHARS} characters is cut after its last whole paragraph that fits. A letter
   * without any {@code ## } section is one post (the whole text).
   *
   * @param markdown the letter, or {@code null}
   * @return the plain-text posts in file order, each 1..{@value #MAX_CHARS} characters; empty when none is usable
   */
  public static List<String> splitPosts(String markdown) {
    List<String> posts = new ArrayList<>();
    if (markdown == null) {
      return posts;
    }
    String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    boolean sectioned = false;
    for (String line : lines) {
      if (line.startsWith("## ")) {
        sectioned = true;
        break;
      }
    }
    if (!sectioned) {
      String whole = toPlainText(markdown);
      if (!whole.isEmpty()) {
        posts.add(whole);
      }
      return posts;
    }
    String heading = null;
    StringBuilder body = new StringBuilder();
    for (String line : lines) {
      if (line.startsWith("## ")) {
        addPost(posts, heading, body);
        heading = line.substring(3);
        body.setLength(0);
      } else if (heading != null) {
        body.append(line).append('\n');
      }
    }
    addPost(posts, heading, body);
    return posts;
  }

  /** Adds the section as a post unless it has no heading text or no body text. */
  private static void addPost(List<String> posts, String heading, StringBuilder body) {
    if (heading == null || toPlainText(body.toString()).isEmpty()) {
      return;
    }
    String post = toPlainText(heading + "\n\n" + body);
    if (!post.isEmpty()) {
      posts.add(post);
    }
  }

  /** Keeps the whole paragraphs that fit in {@link #MAX_CHARS}; logs a warning because text is dropped. */
  private static String cutAtParagraph(String plain) {
    int end = 0;
    int from = 0;
    while (true) {
      int gap = plain.indexOf("\n\n", from);
      int paragraphEnd = gap < 0 ? plain.length() : gap;
      if (paragraphEnd > MAX_CHARS) {
        break;
      }
      end = paragraphEnd;
      if (gap < 0) {
        break;
      }
      from = gap + 2;
    }
    log.warn("letter_cut length={} kept={}", plain.length(), end);
    return plain.substring(0, end);
  }
}
