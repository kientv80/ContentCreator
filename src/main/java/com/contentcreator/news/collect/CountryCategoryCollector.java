package com.contentcreator.news.collect;

import com.contentcreator.gemini.GeminiClient;
import com.contentcreator.gemini.GeminiException;
import com.contentcreator.gemini.GeminiResult;
import com.contentcreator.news.NewsCategory;
import com.contentcreator.news.NewsDraft;
import com.contentcreator.news.NewsDraftRepository;
import com.contentcreator.news.NewsFileParser;
import com.contentcreator.news.StoryUrl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Collects the news of ONE country and ONE category: budget check, prompt, one Gemini call, story filter, parse, store
 * the drafts (unpublished) with their source URLs, and one {@code news_collection_run} row for the attempted call.
 * Any failure is caught, recorded as a failed run row and never propagated, so the caller's loop always continues.
 * Nothing it logs or stores contains the key, the prompt or a response body.
 */
@Component
public class CountryCategoryCollector {

  /**
   * What one {@link #collect} call did.
   *
   * @param budgetReached true when the daily budget was already used up and nothing was called or recorded
   * @param keyMissing true when no API key was available (recorded as {@code KEY_MISSING})
   * @param failed true when the call or its processing failed (recorded with a short error status)
   * @param stories stories in the answer (kept plus rejected)
   * @param stored stories stored in drafts
   * @param rejected stories dropped by the filter or because they were duplicates
   */
  public record Outcome(boolean budgetReached, boolean keyMissing, boolean failed, int stories, int stored, int rejected) {}

  private static final Logger log = LoggerFactory.getLogger(CountryCategoryCollector.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

  private final GeminiClient gemini;
  private final CollectionRunLogRepository runLog;
  private final NewsDraftRepository drafts;
  private final SeenChecker seen;
  private final CollectProperties props;
  private final Clock clock;

  /**
   * @param gemini the Gemini client
   * @param runLog the run log (budget count and run rows)
   * @param drafts stores the drafts, extras and source URLs
   * @param seen finds URLs and titles already collected for the country
   * @param props the collection switches (grounding, title window)
   * @param clock the time source
   */
  public CountryCategoryCollector(
      GeminiClient gemini, CollectionRunLogRepository runLog, NewsDraftRepository drafts, SeenChecker seen, CollectProperties props, Clock clock) {
    this.gemini = gemini;
    this.runLog = runLog;
    this.drafts = drafts;
    this.seen = seen;
    this.props = props;
    this.clock = clock;
  }

  /** Running totals of one call, kept outside the try block so a failure still records what happened before it. */
  private static final class Tally {
    int stories;
    int stored;
    int rejected;
  }

  /**
   * Runs one collection call. When today's (Vietnam day) run rows already reach {@code callsPerDay} it logs
   * {@code news_collect_budget_reached} and returns without calling Google or recording a row.
   *
   * @param country the country with its language, notes and sites
   * @param category the category to collect
   * @param settings the global template and limits
   * @param apiKey the resolved Gemini key, blank when none (recorded as {@code KEY_MISSING})
   * @return what happened; never throws
   */
  public Outcome collect(CountryConfig country, NewsCategory category, CollectionSettings settings, String apiKey) {
    Instant started = clock.instant();
    int used = runLog.countCallsSince(startOfVietnamDay(started));
    if (used >= settings.callsPerDay()) {
      log.info("news_collect_budget_reached used={} limit={}", used, settings.callsPerDay());
      return new Outcome(true, false, false, 0, 0, 0);
    }
    long t0 = System.nanoTime();
    Tally tally = new Tally();
    boolean keyMissing = false;
    String error = null;
    try {
      String prompt = PromptBuilder.build(new PromptBuilder.Input(
          settings.promptTemplate(), country.name(), languageName(country.language()), categoryName(category, country.language()),
          country.sites(), started, settings.storiesPerCategory(), country.notes()));
      GeminiResult result = gemini.generate(prompt, apiKey);
      if (result.keyMissing()) {
        keyMissing = true;
        error = "KEY_MISSING";
      } else {
        process(result, country, category, settings, started, tally);
      }
    } catch (GeminiException e) {
      error = e.googleStatus();
    } catch (IllegalArgumentException e) {
      error = "INVALID_ANSWER";
      log.warn("news_collect_answer_unreadable country={} category={}", country.code(), category.code());
    } catch (RuntimeException e) {
      error = "ERROR";
      log.error("news_collect_call_failed country={} category={} type={}", country.code(), category.code(), e.getClass().getSimpleName());
    }
    int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
    boolean failed = error != null;
    record(new CollectionRunLogRepository.Run(started, country.code(), category.code(), tally.stories, tally.stored, tally.rejected, failed, error, ms));
    log.info("news_collect_call country={} category={} stories={} stored={} ms={}", country.code(), category.code(), tally.stories, tally.stored, ms);
    return new Outcome(false, keyMissing, failed, tally.stories, tally.stored, tally.rejected);
  }

  /**
   * Filters the answer, keeps the first N stories, parses them into drafts filed under the requested category and stores
   * each draft that has at least one new story. A draft whose insert reports a duplicate counts its stories as rejected.
   * The cap is applied after filtering and before parsing, because parsing renders the stories into one summary text.
   * A call asks for one category, so the source URLs of all kept stories go with the first draft (a model that returns
   * extra sections is not given a second set of URL rows).
   */
  private void process(GeminiResult result, CountryConfig country, NewsCategory category, CollectionSettings settings, Instant now, Tally tally) {
    StoryFilter filter = new StoryFilter(seen, props.requireGrounding(), props.titleDedupeDays(), clock);
    StoryFilter.Result filtered = filter.filter(
        result.text(), country.code(), country.sites().stream().map(PromptBuilder.Site::domain).toList(), result.groundingDomains());
    int dropped = filtered.rejected().values().stream().mapToInt(Integer::intValue).sum();
    tally.stories = filtered.kept().size() + dropped;
    tally.rejected = dropped;
    Capped capped = cap(filtered.json(), filtered.kept(), Math.max(1, settings.storiesPerCategory()));
    ZonedDateTime local = now.atZone(VIETNAM);
    List<NewsDraft> parsed = NewsFileParser.parseCollected(
        capped.json(), local.toLocalDate(), code -> Optional.of(category), country.language(), local.toLocalDateTime());
    List<StoryUrl> urls = capped.kept();
    for (NewsDraft draft : parsed) {
      int count = draft.summary().split("\n\n").length; // one paragraph per story written
      if (drafts.insert(draft, country.code(), country.language(), now, urls)) {
        tally.stored += count;
      } else {
        tally.rejected += count;
        log.info("news_collect_rejected country={} reason=duplicate", country.code());
      }
      urls = List.of();
    }
  }

  /** The kept stories after the cap: the answer JSON and the URLs, both limited to the first N stories. */
  record Capped(String json, List<StoryUrl> kept) {}

  /**
   * Keeps the first {@code max} stories (in answer order, across sections) of an already filtered answer.
   *
   * @param json the filtered answer ({@code sections}: an array of stories or an object with {@code items})
   * @param kept the URLs of the kept stories in the same order
   * @param max the story limit, at least 1
   * @return the trimmed JSON and URL list
   */
  static Capped cap(String json, List<StoryUrl> kept, int max) {
    try {
      JsonNode root = MAPPER.readTree(json);
      int left = max;
      JsonNode sections = root.path("sections");
      for (String name : sections.properties().stream().map(java.util.Map.Entry::getKey).toList()) {
        JsonNode section = sections.get(name);
        boolean items = !section.isArray();
        JsonNode stories = items ? section.path("items") : section;
        if (!stories.isArray()) continue;
        ArrayNode trimmed = MAPPER.createArrayNode();
        for (JsonNode story : stories) {
          if (left > 0) {
            trimmed.add(story);
            left--;
          }
        }
        if (items) {
          ((ObjectNode) section).set("items", trimmed);
        } else {
          ((ObjectNode) sections).set(name, trimmed);
        }
      }
      return new Capped(root.toString(), List.copyOf(new ArrayList<>(kept.subList(0, Math.min(max, kept.size())))));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("Not valid JSON", e);
    }
  }

  /** Writes the run row; a database failure is logged (type only) and never propagated. */
  private void record(CollectionRunLogRepository.Run run) {
    try {
      runLog.insert(run);
    } catch (RuntimeException e) {
      log.error("news_collect_run_log_failed country={} category={} type={}", run.country(), run.category(), e.getClass().getSimpleName());
    }
  }

  /**
   * The instant the current Vietnam day (UTC+7) began.
   *
   * @param now the current instant
   * @return midnight of that Vietnam day
   */
  static Instant startOfVietnamDay(Instant now) {
    return now.atZone(VIETNAM).toLocalDate().atStartOfDay(VIETNAM).toInstant();
  }

  /**
   * The English name of a language code for the prompt, e.g. {@code en} gives "English".
   *
   * @param code the language code
   * @return the display name, or the code itself when unknown
   */
  static String languageName(String code) {
    String name = Locale.forLanguageTag(code == null ? "" : code).getDisplayLanguage(Locale.ENGLISH);
    return name.isBlank() ? String.valueOf(code) : name;
  }

  /**
   * The category name in the news language: English for {@code en}, Vietnamese otherwise.
   *
   * @param category the category
   * @param language the news language code
   * @return the name shown in the prompt
   */
  static String categoryName(NewsCategory category, String language) {
    return "en".equals(language) ? category.nameEn() : category.nameVi();
  }
}
