package com.contentcreator.news;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes straight into ElderEase's existing {@code news_summary} table (V20, plus {@code category} from V40). Rows are always inserted
 * unpublished ({@code published = FALSE}, no {@code published_at}); the country and language come from the caller (the file and
 * Claude paths pass VN and vi); an admin reviews and publishes them on the admin site.
 * Each draft's story extras go into {@code news_story_extra} (ElderEase V46) and its source URLs into {@code news_story_source}
 * (V62) in the same transaction.
 * It never updates or deletes anything.
 */
@Repository
public class NewsDraftRepository {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public NewsDraftRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
  }

  /** Whether a summary with the same headline and day already exists for Vietnam (any state); the file and Claude paths use this. */
  public boolean exists(NewsDraft draft) {
    return exists(draft, "VN");
  }

  /**
   * Whether a summary with the same headline and day already exists for the country (any state).
   *
   * @param draft the draft to look for
   * @param country the two-letter country code
   * @return true when a row with that headline, day and country exists
   */
  public boolean exists(NewsDraft draft, String country) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM news_summary WHERE headline = ? AND news_date = ? AND country_code = ?",
        Integer.class, draft.headline(), java.sql.Date.valueOf(draft.newsDate()), country);
    return count != null && count > 0;
  }

  /** Inserts the draft for Vietnam in Vietnamese with no source URLs (the file and Claude paths); see the long form. */
  public void insert(NewsDraft draft, Instant now) {
    insert(draft, "VN", "vi", now, List.of());
  }

  /**
   * Inserts the draft (unpublished) with its category (needs ElderEase migration V40), its story extras (V46) and one
   * {@code news_story_source} row per URL (V62) in one transaction: if any insert fails nothing is stored.
   * A unique-key violation on the source rows {@code (country_code, url_hash)} (a retry or a second instance got there first)
   * rolls everything back and is reported as {@code false}; any other failure is thrown.
   *
   * @param draft the draft to store
   * @param country the two-letter country code written to {@code country_code}
   * @param language the language code written to {@code language}
   * @param now the creation time
   * @param urls the article URLs the draft was built from, may be empty
   * @return true when stored, false when a URL was already collected for the country (nothing stored)
   */
  public boolean insert(NewsDraft draft, String country, String language, Instant now, List<StoryUrl> urls) {
    Timestamp ts = Timestamp.from(now);
    String id = UUID.randomUUID().toString();
    AtomicBoolean duplicate = new AtomicBoolean();
    tx.executeWithoutResult(status -> {
        jdbc.update(
            "INSERT INTO news_summary (id, headline, summary, news_date, country_code, language, category, published, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)",
            id, draft.headline(), draft.summary(), java.sql.Date.valueOf(draft.newsDate()), country, language, draft.category(), ts, ts);
        for (StoryExtra extra : draft.extras()) {
          jdbc.update(
              "INSERT INTO news_story_extra (summary_id, story_index, story_title, key_points, analysis, created_at) VALUES (?, ?, ?, ?, ?, ?)",
              id, extra.index(), extra.title(), extra.keyPoints().isEmpty() ? null : String.join("\n", extra.keyPoints()),
              extra.analysis(), ts);
        }
        for (StoryUrl url : urls) {
          try {
            jdbc.update(
                "INSERT INTO news_story_source (country_code, url_hash, url, summary_id, created_at) VALUES (?, ?, ?, ?, ?)",
                country, url.hash(), cut(url.url(), MAX_URL), id, ts);
          } catch (DuplicateKeyException e) {
            duplicate.set(true);
            status.setRollbackOnly();
            return;
          }
        }
      });
    return !duplicate.get();
  }

  /** The news_story_source.url column limit. */
  static final int MAX_URL = 2000;

  private static String cut(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }
}
