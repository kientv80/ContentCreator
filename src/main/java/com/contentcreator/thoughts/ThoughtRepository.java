package com.contentcreator.thoughts;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes Shared Thoughts straight into ElderEase's {@code poems} table (V19, text column 6000 from V43) and looks
 * up the system accounts in {@code users}. It never updates or deletes anything.
 */
@Repository
public class ThoughtRepository {

  private final JdbcTemplate jdbc;

  public ThoughtRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Finds a user by name.
   *
   * @param username the exact username
   * @return the user's id, or empty when there is no such user
   */
  public Optional<String> findWriterId(String username) {
    List<String> ids = jdbc.queryForList("SELECT id FROM users WHERE username = ?", String.class, username);
    return ids.stream().findFirst();
  }

  /**
   * Whether the writer already has a thought for that day, in any state (including deleted).
   *
   * @param writerId the writer's user id
   * @param localDate the day of the letter
   * @return true when a row exists
   */
  public boolean exists(String writerId, LocalDate localDate) {
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM poems WHERE writer_id = ? AND local_date = ?", Integer.class, writerId, Date.valueOf(localDate));
    return count != null && count > 0;
  }

  /**
   * Inserts a public thought waiting for moderation (PUBLIC, PENDING, no voice).
   *
   * @param writerId the writer's user id
   * @param text the plain text (1..6000 characters)
   * @param localDate the day of the letter
   * @param now the creation time
   */
  public void insertPending(String writerId, String text, LocalDate localDate, Instant now) {
    Timestamp ts = Timestamp.from(now);
    jdbc.update(
        "INSERT INTO poems (id, writer_id, content_text, privacy, approval_status, local_date, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'PUBLIC', 'PENDING', ?, ?, ?)",
        UUID.randomUUID().toString(), writerId, text, Date.valueOf(localDate), ts, ts);
  }
}
