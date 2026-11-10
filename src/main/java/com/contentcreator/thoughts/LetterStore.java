package com.contentcreator.thoughts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Stores the posts of one letter as pending Shared Thoughts. Shared by the folder worker and the intake API so both
 * insert exactly the same way.
 */
public class LetterStore {

  private final ThoughtRepository repository;

  /** @param repository the thought table access */
  public LetterStore(ThoughtRepository repository) {
    this.repository = repository;
  }

  /**
   * Inserts the posts newest-first (the last post first, so Post 1 gets the latest created_at and is listed on top)
   * and skips a post whose heading line the writer already has on that day, in any state.
   *
   * @param writerId the writer's user id
   * @param day the letter's day
   * @param posts the plain-text posts in letter order
   * @param base the creation time of the last post; each earlier post is one second later
   * @return the number of posts inserted
   * @throws org.springframework.dao.DataAccessException on a database error
   */
  public int store(String writerId, LocalDate day, List<String> posts, Instant base) {
    int stored = 0;
    for (int i = posts.size() - 1; i >= 0; i--) {
      String post = posts.get(i);
      if (repository.existsPost(writerId, day, post.lines().findFirst().orElse(post))) {
        continue;
      }
      repository.insertPending(writerId, post, day, base.plusSeconds(posts.size() - 1 - i));
      stored++;
    }
    return stored;
  }
}
