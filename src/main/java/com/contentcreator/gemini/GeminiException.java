package com.contentcreator.gemini;

/**
 * A failed Gemini call. The message holds only the HTTP status and Google's error status text (for example
 * {@code RESOURCE_EXHAUSTED}), never the request, the URL, the response body or the API key.
 */
public class GeminiException extends RuntimeException {

  private final int httpStatus;
  private final String googleStatus;

  /**
   * @param httpStatus the HTTP status, or 0 when no response arrived
   * @param googleStatus Google's {@code error.status} text, or a fixed code such as {@code TIMEOUT}
   */
  public GeminiException(int httpStatus, String googleStatus) {
    super("Gemini call failed: HTTP " + httpStatus + " " + googleStatus);
    this.httpStatus = httpStatus;
    this.googleStatus = googleStatus;
  }

  /** @return the HTTP status, or 0 when no response arrived */
  public int httpStatus() {
    return httpStatus;
  }

  /** @return Google's error status text or a fixed code such as TIMEOUT or UNREACHABLE */
  public String googleStatus() {
    return googleStatus;
  }
}
