package com.contentcreator.news.intake;

import com.contentcreator.news.CategoryRepository;
import com.contentcreator.news.NewsDraft;
import com.contentcreator.news.NewsDraftRepository;
import com.contentcreator.news.NewsFileParser;
import com.contentcreator.news.StoryUrl;
import com.contentcreator.news.collect.CollectionConfigRepository;
import com.contentcreator.news.collect.StoryFilter;
import com.contentcreator.news.collect.UrlSeenChecker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * Turns a posted bulletin into unpublished drafts, section by section: {@link StoryFilter} (grounding rule off) drops
 * stories that fail the https, host and duplicate checks, {@link NewsFileParser} writes the draft, and
 * {@link NewsDraftRepository#insert} stores it with its country, language and source URLs in one transaction.
 */
@Service
@ConditionalOnExpression("!'${contentcreator.news.intake.token-sha256:}'.isBlank()")
public class NewsIntakeService {

  /** Outcome of one intake call. */
  public enum Status { CREATED, ALREADY_STORED, NO_VALID_STORY, UNKNOWN_COUNTRY, INVALID_LANGUAGE }

  /** One reject reason with its story count. */
  public record Rejected(String reason, int count) {}

  /**
   * The result of one intake call.
   *
   * @param status what happened
   * @param received stories in the request
   * @param stored stories saved as drafts
   * @param skipped stories not saved (rejected or already stored)
   * @param rejected the skipped stories grouped by reason
   */
  public record Result(Status status, int received, int stored, int skipped, List<Rejected> rejected) {}

  private static final Logger log = LoggerFactory.getLogger(NewsIntakeService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final CollectionConfigRepository config;
  private final CategoryRepository categories;
  private final NewsDraftRepository drafts;
  private final StoryFilter filter;
  private final Clock clock;

  /**
   * @param config country and trusted-site lookups
   * @param categories the category lookup for the parser
   * @param drafts the draft store
   * @param seen the stored-URL check (the filter's duplicate rule)
   * @param clock the time source
   */
  public NewsIntakeService(
      CollectionConfigRepository config, CategoryRepository categories, NewsDraftRepository drafts, UrlSeenChecker seen, Clock clock) {
    this.config = config;
    this.categories = categories;
    this.drafts = drafts;
    this.filter = new StoryFilter(seen, false);
    this.clock = clock;
  }

  /**
   * Stores the valid stories of a bulletin.
   *
   * @param root the request body, already known to be a JSON object
   * @return the outcome; never throws for bad content
   */
  public Result process(JsonNode root) {
    String country = text(root, "country", "VN").toUpperCase(Locale.ROOT);
    String language = text(root, "language", "vi");
    if (!country.matches("[A-Z]{2}") || !config.countryExists(country)) {
      return fail(Status.UNKNOWN_COUNTRY, "unknown_country");
    }
    if (!language.matches("[a-z]{2}")) {
      return fail(Status.INVALID_LANGUAGE, "invalid_language");
    }
    List<String> allowed = config.siteDomains(country);
    JsonNode sections = root.path("sections");
    int received = 0;
    int sectionCount = 0;
    int stored = 0;
    int storedDrafts = 0;
    Map<StoryFilter.Reason, Integer> rejected = new EnumMap<>(StoryFilter.Reason.class);
    if (sections.isObject()) {
      for (String name : sections.properties().stream().map(Map.Entry::getKey).toList()) {
        JsonNode section = sections.get(name);
        JsonNode stories = section.isArray() ? section : section.path("items");
        if (!stories.isArray()) continue;
        sectionCount++;
        received += stories.size();
        ObjectNode single = MAPPER.createObjectNode();
        single.set("date", root.path("date"));
        single.putObject("sections").set(name, section);
        StoryFilter.Result filtered = filter.filter(single.toString(), country, allowed, List.of(), allowed.isEmpty());
        filtered.rejected().forEach((r, n) -> rejected.merge(r, n, Integer::sum));
        List<NewsDraft> parsed = NewsFileParser.parse(filtered.json(), LocalDate.now(clock), categories::findActive, language);
        if (parsed.isEmpty()) {
          rejected.merge(StoryFilter.Reason.EMPTY, filtered.kept().size(), Integer::sum);
          continue;
        }
        Instant now = clock.instant();
        List<StoryUrl> urls = filtered.kept();
        if (drafts.insert(parsed.get(0), country, language, now, urls)) {
          stored += urls.size();
          storedDrafts++;
        } else {
          rejected.merge(StoryFilter.Reason.DUPLICATE, urls.size(), Integer::sum);
        }
      }
    }
    log.info("news_intake_received country={} sections={} stories={}", country, sectionCount, received);
    List<Rejected> reasons = new ArrayList<>();
    rejected.forEach((r, n) -> {
      if (n > 0) {
        reasons.add(new Rejected(r.name().toLowerCase(Locale.ROOT), n));
        log.info("news_intake_rejected reason={} count={}", r.name().toLowerCase(Locale.ROOT), n);
      }
    });
    int skipped = received - stored;
    if (stored > 0) {
      log.info("news_intake_stored country={} drafts={} stories={}", country, storedDrafts, stored);
      return new Result(Status.CREATED, received, stored, skipped, reasons);
    }
    boolean onlyDuplicates = rejected.getOrDefault(StoryFilter.Reason.DUPLICATE, 0) > 0;
    return new Result(onlyDuplicates ? Status.ALREADY_STORED : Status.NO_VALID_STORY, received, 0, skipped, reasons);
  }

  private static Result fail(Status status, String reason) {
    log.info("news_intake_rejected reason={}", reason);
    return new Result(status, 0, 0, 0, List.of());
  }

  /** A top-level text field; the default when it is missing, null or blank; "?" (never valid) when it is not text. */
  private static String text(JsonNode root, String field, String fallback) {
    JsonNode n = root.path(field);
    if (n.isMissingNode() || n.isNull()) return fallback;
    if (!n.isTextual()) return "?";
    return n.asText().isBlank() ? fallback : n.asText().trim();
  }
}
