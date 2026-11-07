package com.contentcreator.gemini;

import java.util.List;

/**
 * The outcome of one Gemini call.
 *
 * @param text the answer text (the text parts of the first candidate joined by newlines), empty when none
 * @param groundingDomains publisher domains Google Search used (lower-case {@code groundingChunks[].web.title}), no duplicates
 * @param keyMissing true when no API key was configured and Google was not called
 */
public record GeminiResult(String text, List<String> groundingDomains, boolean keyMissing) {

  public GeminiResult {
    text = text == null ? "" : text;
    groundingDomains = groundingDomains == null ? List.of() : List.copyOf(groundingDomains);
  }

  /** @return the distinct result for "no key configured": no text, no domains, {@code keyMissing} true */
  public static GeminiResult missingKey() {
    return new GeminiResult("", List.of(), true);
  }
}
