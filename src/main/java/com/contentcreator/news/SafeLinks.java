package com.contentcreator.news;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict rules for a story link that is shown to elders as a tappable name. A link is used only when the whole
 * URL is plain, honest ASCII-style http(s): no user info, no raw IP address, no punycode or non-ASCII host, and
 * no hidden or look-alike characters. Anything else is shown as plain text by the caller.
 */
final class SafeLinks {

  static final int MAX_LENGTH = 500;
  private static final Pattern URL = Pattern.compile("^(?i:https?)://([^/?#]*)([/?#].*)?$", Pattern.DOTALL);
  private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$");
  private static final Pattern PORT = Pattern.compile("^[0-9]{1,5}$");

  private SafeLinks() {}

  /**
   * @param url the link text from the bulletin
   * @return true only when the URL passes every display-safety rule (see class doc)
   */
  static boolean isSafe(String url) {
    return host(url) != null;
  }

  /**
   * @param url the link text
   * @return the lower-case host of a safe URL, or {@code null} when the URL is not safe
   */
  static String host(String url) {
    if (url == null || url.isEmpty() || url.length() > MAX_LENGTH || hasForbiddenCharacter(url)) {
      return null;
    }
    Matcher m = URL.matcher(url);
    if (!m.matches()) {
      return null;
    }
    String authority = m.group(1);
    int colon = authority.indexOf(':');
    String host = colon < 0 ? authority : authority.substring(0, colon);
    if (colon >= 0 && !PORT.matcher(authority.substring(colon + 1)).matches()) {
      return null; // also rejects '@' (user info), a second ':' and bracketed IPv6
    }
    return validHost(host) ? host.toLowerCase(Locale.ROOT) : null;
  }

  /**
   * Checks a source label against a link host. A label that looks like a domain (has a dot, no spaces) must be the
   * host or a parent domain of it ({@code www.} is ignored); any other label (an outlet name) always matches.
   *
   * @param label the story's source name
   * @param host the lower-case host of a safe URL
   * @return false when a domain-like label names a different site than the link goes to
   */
  static boolean labelMatchesHost(String label, String host) {
    String l = label.toLowerCase(Locale.ROOT);
    if (!l.contains(".") || l.chars().anyMatch(Character::isWhitespace)) {
      return true;
    }
    l = stripWww(l);
    String h = stripWww(host);
    return h.equals(l) || h.endsWith("." + l);
  }

  private static String stripWww(String s) {
    return s.startsWith("www.") ? s.substring(4) : s;
  }

  private static boolean validHost(String host) {
    String[] labels = host.split("\\.", -1);
    if (labels.length < 2) {
      return false;
    }
    for (String label : labels) {
      if (!LABEL.matcher(label).matches() || label.regionMatches(true, 0, "xn--", 0, 4)) {
        return false;
      }
    }
    return !labels[labels.length - 1].chars().allMatch(c -> c >= '0' && c <= '9'); // 1.2.3.4 or 2130706433
  }

  private static boolean hasForbiddenCharacter(String url) {
    return url.codePoints().anyMatch(c -> {
      int type = Character.getType(c);
      return c == '\\' || c == '*' || c == '[' || c == ']' || c == '<' || c == '>' || c == '"'
          || Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c)
          || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
          || type == Character.UNASSIGNED || type == Character.PRIVATE_USE || type == Character.SURROGATE;
    });
  }
}
