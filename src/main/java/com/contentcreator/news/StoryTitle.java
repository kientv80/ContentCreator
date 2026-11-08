package com.contentcreator.news;

import java.text.Normalizer;
import java.util.Locale;

/** Title normalisation for duplicate detection (SPEC FR-506): the same headline in another case or accent matches. */
public final class StoryTitle {

  private StoryTitle() {}

  /**
   * Lower case, accents removed (Unicode NFD without combining marks, {@code đ} to {@code d}), every run of
   * non-letters and non-digits turned into one space, trimmed.
   *
   * @param title the story title, may be null
   * @return the normalized title, empty when nothing alphanumeric is left
   */
  public static String normalize(String title) {
    if (title == null) return "";
    String lower = title.toLowerCase(Locale.ROOT).replace('đ', 'd');
    String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
  }

  /**
   * SHA-256 of the normalized title.
   *
   * @param title the story title, may be null
   * @return 64 lower-case hex characters, or {@code null} when the normalized title is empty (no title check then)
   */
  public static String hash(String title) {
    String normalized = normalize(title);
    return normalized.isEmpty() ? null : StoryUrl.hash(normalized);
  }
}
