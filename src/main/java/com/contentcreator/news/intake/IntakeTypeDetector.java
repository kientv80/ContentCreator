package com.contentcreator.news.intake;

import com.fasterxml.jackson.databind.JsonNode;

/** Decides from the body whether an intake request is a news bulletin or a letter (FR-571). */
public final class IntakeTypeDetector {

  /** The kind of request. */
  public enum Type { NEWS, LETTER, AMBIGUOUS }

  private IntakeTypeDetector() {}

  /**
   * A body with a text {@code letter} and no {@code sections} is a letter; one with {@code sections} and no text
   * {@code letter} (or neither) is news; one with both is ambiguous.
   *
   * @param root the parsed JSON object
   * @return the detected type
   */
  public static Type detect(JsonNode root) {
    boolean letter = root.path("letter").isTextual();
    boolean sections = root.has("sections");
    if (letter && sections) {
      return Type.AMBIGUOUS;
    }
    return letter ? Type.LETTER : Type.NEWS;
  }
}
