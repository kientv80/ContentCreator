package com.contentcreator.news;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes straight into ElderEase's existing {@code news_summary} table (V20, plus {@code category} from V40). Rows are always inserted
 * unpublished ({@code published = FALSE}, no {@code published_at}), for Vietnam ({@code country_code = 'VN'})
 * and Vietnamese ({@code language = 'vi'}); an admin reviews and publishes them on the admin site.
 * It never updates or deletes anything.
 */
@Repository
public class NewsDraftRepository {

  private final JdbcTemplate jdbc;

  public NewsDraftRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Whether a summary with the same headline and day already exists for Vietnam (any state). */
  public boolean exists(NewsDraft draft) {
    Integer count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM news_summary WHERE headline = ? AND news_date = ? AND country_code = 'VN'",
        Integer.class, draft.headline(), java.sql.Date.valueOf(draft.newsDate()));
    return count != null && count > 0;
  }

  /** Inserts the draft, including its category (needs ElderEase migration V40). */
  public void insert(NewsDraft draft, Instant now) {
    Timestamp ts = Timestamp.from(now);
    jdbc.update(
        "INSERT INTO news_summary (id, headline, summary, news_date, country_code, language, category, published, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'VN', 'vi', ?, FALSE, ?, ?)",
        UUID.randomUUID().toString(), draft.headline(), draft.summary(), java.sql.Date.valueOf(draft.newsDate()),
        draft.category(), ts, ts);
  }
}
