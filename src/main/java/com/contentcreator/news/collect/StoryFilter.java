package com.contentcreator.news.collect;

import com.contentcreator.news.StoryUrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filters the stories in the model's JSON answer before {@code NewsFileParser} sees them. Each story passes a chain
 * of independent checks and is dropped with the first reason that fails: {@code host} (not https, a Google or
 * redirect host, not an allow-listed site, or not a site Google Search reported), {@code duplicate} (URL already
 * collected for the country, or repeated in this answer), {@code empty} (no title or no summary).
 * It never fetches any URL.
 */
public class StoryFilter {

  /** Why a story was dropped; the lower-case name is what the log line shows. */
  public enum Reason { HOST, DUPLICATE, EMPTY }

  /**
   * What the filter produced.
   *
   * @param json the answer with only the kept stories (same structure otherwise), ready for the parser
   * @param kept the URL (hash and text) of every kept story, in order
   * @param rejected how many stories were dropped per reason
   */
  public record Result(String json, List<StoryUrl> kept, Map<Reason, Integer> rejected) {}

  private static final Logger log = LoggerFactory.getLogger(StoryFilter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern HOST = Pattern.compile("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z0-9-]+$");
  /** Second-level labels that sit under a two-letter country suffix, as in co.uk or com.vn. */
  private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "net", "org", "gov", "edu", "ac");

  private final UrlSeenChecker seen;
  private final boolean requireGrounding;

  /**
   * @param seen finds URLs already collected for a country
   * @param requireGrounding whether the grounding-domain check applies
   */
  public StoryFilter(UrlSeenChecker seen, boolean requireGrounding) {
    this.seen = seen;
    this.requireGrounding = requireGrounding;
  }

  /**
   * Drops every story that fails a check and logs {@code news_collect_rejected} for each.
   *
   * @param answer the model's text: a JSON object, optionally wrapped in a code fence or other text
   * @param country the two-letter country code
   * @param allowedDomains the country's allow-listed site domains
   * @param groundingDomains the publisher domains Google Search reported (lower-case)
   * @return the filtered JSON, the kept URLs and the reject counts
   * @throws IllegalArgumentException when the answer holds no JSON object
   */
  public Result filter(String answer, String country, List<String> allowedDomains, List<String> groundingDomains) {
    JsonNode parsed = parseObject(answer);
    ObjectNode root = (ObjectNode) parsed;
    Set<String> grounded = new HashSet<>();
    for (String g : groundingDomains) {
      String d = normalizeDomain(g);
      if (d != null) grounded.add(registrableDomain(d));
    }
    List<String> allowed = allowedDomains.stream().map(StoryFilter::normalizeDomain).filter(d -> d != null).toList();
    Set<String> inAnswer = new HashSet<>();
    List<StoryUrl> kept = new ArrayList<>();
    Map<Reason, Integer> rejected = new EnumMap<>(Reason.class);
    for (Reason r : Reason.values()) rejected.put(r, 0);

    JsonNode sections = root.path("sections");
    if (sections.isObject()) {
      for (String name : sections.properties().stream().map(Map.Entry::getKey).toList()) {
        JsonNode section = sections.get(name);
        boolean items = !section.isArray();
        JsonNode stories = items ? section.path("items") : section;
        if (!stories.isArray()) continue;
        ArrayNode keptStories = MAPPER.createArrayNode();
        for (JsonNode story : stories) {
          Reason reason = check(story, country, allowed, grounded, inAnswer);
          if (reason == null) {
            String url = story.get("url").asText();
            kept.add(new StoryUrl(hash(normalize(url)), url));
            keptStories.add(story);
          } else {
            rejected.merge(reason, 1, Integer::sum);
            log.info("news_collect_rejected country={} reason={}", country, reason.name().toLowerCase(Locale.ROOT));
          }
        }
        if (items) {
          ((ObjectNode) section).set("items", keptStories);
        } else {
          ((ObjectNode) sections).set(name, keptStories);
        }
      }
    }
    return new Result(root.toString(), List.copyOf(kept), rejected);
  }

  /** Runs the chain on one story; {@code null} means it passed. A passing story's hash is remembered for this answer. */
  private Reason check(JsonNode story, String country, List<String> allowed, Set<String> grounded, Set<String> inAnswer) {
    String url = story.isObject() && story.path("url").isTextual() ? story.get("url").asText() : null;
    String host = host(url);
    if (host == null || isGoogle(host) || !onAllowList(host, allowed)) {
      return Reason.HOST;
    }
    if (requireGrounding && !grounded.contains(registrableDomain(host))) {
      return Reason.HOST;
    }
    String hash = hash(normalize(url));
    if (inAnswer.contains(hash) || seen.seen(country, hash)) {
      return Reason.DUPLICATE;
    }
    if (blank(story, "title") || blank(story, "summary")) {
      return Reason.EMPTY;
    }
    inAnswer.add(hash);
    return null;
  }

  /** Whether a text field is missing, not text, or only whitespace. */
  private static boolean blank(JsonNode story, String field) {
    return !story.path(field).isTextual() || story.get(field).asText().isBlank();
  }

  /** Reads the first-to-last-brace JSON object out of the answer. */
  private static JsonNode parseObject(String answer) {
    int start = answer == null ? -1 : answer.indexOf('{');
    int end = answer == null ? -1 : answer.lastIndexOf('}');
    if (start < 0 || end < start) {
      throw new IllegalArgumentException("Not a JSON object");
    }
    try {
      JsonNode node = MAPPER.readTree(answer.substring(start, end + 1));
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException("Not a JSON object");
      }
      return node;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Not valid JSON", e);
    }
  }

  /**
   * The lower-case host of a plain https URL: no user info, no port other than 443, ASCII letters, digits, dots and hyphens only.
   *
   * @param url the story URL, may be null
   * @return the host without a trailing dot, or {@code null} when the URL is not acceptable
   */
  static String host(String url) {
    if (url == null) return null;
    try {
      URI uri = new URI(url.trim());
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
        return null;
      }
      String host = uri.getHost();
      if (host == null) return null;
      host = host.toLowerCase(Locale.ROOT);
      if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
      return HOST.matcher(host).matches() ? host : null;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** Google's own hosts and its search redirect host are never publisher pages. */
  static boolean isGoogle(String host) {
    return host.equals("google.com") || host.endsWith(".google.com");
  }

  /** Whether the host is an allow-listed domain or a subdomain of one (label boundary, so evil-x.net is not x.net). */
  static boolean onAllowList(String host, List<String> allowed) {
    return allowed.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
  }

  /**
   * The registrable domain: the last two labels, or the last three under a two-letter country suffix such as
   * {@code co.uk} or {@code com.vn} (a small built-in rule, not the full public suffix list).
   *
   * @param host a lower-case host
   * @return the registrable domain
   */
  static String registrableDomain(String host) {
    String[] labels = host.split("\\.");
    int n = labels.length;
    if (n >= 3 && labels[n - 1].length() == 2 && SECOND_LEVEL.contains(labels[n - 2])) {
      return labels[n - 3] + "." + labels[n - 2] + "." + labels[n - 1];
    }
    return n >= 2 ? labels[n - 2] + "." + labels[n - 1] : host;
  }

  /** A configured or reported domain, lower-case without {@code www.} or a trailing dot; {@code null} when it is not a plain domain. */
  private static String normalizeDomain(String domain) {
    if (domain == null) return null;
    String d = domain.trim().toLowerCase(Locale.ROOT);
    if (d.startsWith("www.")) d = d.substring(4);
    if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
    return HOST.matcher(d).matches() ? d : null;
  }

  /**
   * Normalizes a URL for duplicate detection: https, lower-case host, no port, no fragment, no trailing slash, query kept.
   *
   * @param url an acceptable https URL (see {@link #host(String)})
   * @return the normalized form
   */
  static String normalize(String url) {
    URI uri = URI.create(url.trim());
    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    return "https://" + host(url) + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
  }

  /**
   * SHA-256 of a text as 64 lower-case hex characters.
   *
   * @param text the text, normally a normalized URL
   * @return the hex digest
   */
  static String hash(String text) {
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
