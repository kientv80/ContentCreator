package com.contentcreator.news;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An article link (and its title hash) a draft was built from, remembered so the same article is not collected again.
 *
 * @param hash SHA-256 (64 hex characters) of the normalized URL
 * @param url the article URL as the model gave it
 * @param titleHash SHA-256 (64 hex characters) of the normalized title, or {@code null} when the story has no usable title
 */
public record StoryUrl(String hash, String url, String titleHash) {

  /** Query parameters that only track where a click came from (SPEC FR-506); {@code utm_*} is matched by prefix. */
  private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "mc_cid", "mc_eid", "ref", "ref_src", "igshid");
  private static final Pattern AMPERSAND = Pattern.compile("&");

  /**
   * Normalizes a URL for duplicate detection: https, lower-case host, no port, no fragment, no trailing slash, and the
   * query kept in its order minus the tracking parameters ({@code utm_*}, fbclid, gclid, mc_cid, mc_eid, ref, ref_src, igshid).
   *
   * @param url an acceptable https URL (a host the story filter already accepted)
   * @return the normalized form
   */
  public static String normalize(String url) {
    URI uri = URI.create(url.trim());
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    String query = cleanQuery(uri.getRawQuery());
    return "https://" + host + path + (query.isEmpty() ? "" : "?" + query);
  }

  /**
   * Removes the tracking parameters from a raw query string, keeping the others in order.
   *
   * @param rawQuery the raw query (without the {@code ?}), may be null
   * @return the remaining query, empty when nothing is left
   */
  static String cleanQuery(String rawQuery) {
    if (rawQuery == null) return "";
    StringBuilder out = new StringBuilder();
    for (String pair : AMPERSAND.split(rawQuery)) {
      String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
      String lower = name.toLowerCase(Locale.ROOT);
      if (pair.isEmpty() || lower.startsWith("utm_") || TRACKING.contains(lower)) continue;
      if (out.length() > 0) out.append('&');
      out.append(pair);
    }
    return out.toString();
  }

  /**
   * SHA-256 of a text as 64 lower-case hex characters.
   *
   * @param text the text, normally a normalized URL or title
   * @return the hex digest
   */
  public static String hash(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e); // every JVM ships SHA-256
    }
  }
}
