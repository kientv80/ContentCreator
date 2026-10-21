package com.contentcreator.news;

/** Text clean-up shared by the bulletin and API parsers. */
final class NewsText {

  private NewsText() {}

  /**
   * Removes the characters the app's markup uses, so a story cannot break or fake bold text or links.
   *
   * @param s any text, or {@code null}
   * @return the text without {@code *}, with {@code [ ]} turned into parentheses, trimmed; {@code null} stays {@code null}
   */
  static String clean(String s) {
    return s == null ? null : s.replace("*", "").replace("[", "(").replace("]", ")").trim();
  }
}
