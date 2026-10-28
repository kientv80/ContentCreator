package com.contentcreator.news;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes straight into ElderEase's existing {@code news_summary} table (V20, plus {@code category} from V40). Rows are always inserted
 * unpublished ({@code published = FALSE}, no {@code published_at}), for Vietnam ({@code country_code = 'VN'})
 * and Vietnamese ({@code language = 'vi'}); an admin reviews and publishes them on the admin site.
 * Each draft's story extras go into {@code news_story_extra} (ElderEase V46) in the same transaction.
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

  /** Whether a summary with the same headline and day already exists for Vietnam (any state). */
  public boolean exists(NewsDraft draft) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM news_summary WHERE headline = ? AND news_date = ? AND country_code = 'VN'",
        Integer.class, draft.headline(), java.sql.Date.valueOf(draft.newsDate()));
    return count != null && count > 0;
  }

  /**
   * Inserts the draft with its category (needs ElderEase migration V40) and its story extras (V46) in one transaction:
   * if any insert fails nothing is stored and the exception is thrown.
   */
  public void insert(NewsDraft draft, Instant now) {
    Timestamp ts = Timestamp.from(now);
    String id = UUID.randomUUID().toString();
    tx.executeWithoutResult(status -> {
      jdbc.update(
          "INSERT INTO news_summary (id, headline, summary, news_date, country_code, language, category, published, created_at, updated_at) "
              + "VALUES (?, ?, ?, ?, 'VN', 'vi', ?, FALSE, ?, ?)",
          id, draft.headline(), draft.summary(), java.sql.Date.valueOf(draft.newsDate()), draft.category(), ts, ts);
      for (StoryExtra extra : draft.extras()) {
        jdbc.update(
            "INSERT INTO news_story_extra (summary_id, story_index, story_title, key_points, analysis, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            id, extra.index(), extra.title(), extra.keyPoints().isEmpty() ? null : String.join("\n", extra.keyPoints()),
            extra.analysis(), ts);
      }
    });
  }
}
