package com.contentcreator.news;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public API the Claude coworker calls to submit news summaries as drafts. Protected by a shared secret in
 * the {@code X-API-Key} header ({@code CONTENTCREATOR_INGEST_KEY}); with no secret configured every call is refused.
 */
@RestController
@RequestMapping("/api/v1/news-summaries")
public class NewsPublishController {

  public record PublishResult(int received, int stored) {}

  private final NewsSummaryWorker worker;
  private final Clock clock;
  private final byte[] key;

  public NewsPublishController(NewsSummaryWorker worker, Clock clock, @Value("${contentcreator.ingest.api-key:}") String key) {
    this.worker = worker;
    this.clock = clock;
    this.key = key.getBytes(StandardCharsets.UTF_8);
  }

  /** Stores up to 50 drafts; known ones are skipped. {@code 201} with the counts, {@code 401} for a bad key. */
  @PostMapping
  public ResponseEntity<PublishResult> submit(
      @RequestHeader(value = "X-API-Key", required = false) String provided, @Valid @RequestBody NewsPublishRequest request) {
    if (key.length == 0 || provided == null || !MessageDigest.isEqual(key, provided.getBytes(StandardCharsets.UTF_8))) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    LocalDate today = LocalDate.now(clock.withZone(NewsPublishRequest.VIETNAM));
    int stored = worker.publish(request.items().stream().map(i -> i.toDraft(today)).toList());
    return ResponseEntity.status(HttpStatus.CREATED).body(new PublishResult(request.items().size(), stored));
  }
}
