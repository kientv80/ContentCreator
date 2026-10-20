package com.contentcreator.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores collected stories and reads them back. */
@Service
public class NewsSummaryService {

  private final NewsSummaryRepository repository;
  private final Clock clock;

  public NewsSummaryService(NewsSummaryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Inserts the stories whose link is not stored yet. Returns how many were new. */
  @Transactional
  public int store(List<NewsItem> items) {
    Map<String, NewsItem> byHash = new LinkedHashMap<>();
    for (NewsItem item : items) {
      byHash.putIfAbsent(hash(item.sourceUrl()), item);
    }
    if (byHash.isEmpty()) {
      return 0;
    }
    Set<String> existing = Set.copyOf(repository.findExistingHashes(byHash.keySet()));
    Instant now = clock.instant();
    List<NewsSummary> fresh = byHash.entrySet().stream()
        .filter(e -> !existing.contains(e.getKey()))
        .map(e -> new NewsSummary(e.getValue(), e.getKey(), now))
        .collect(Collectors.toList());
    repository.saveAll(fresh);
    return fresh.size();
  }

  /** Newest first. */
  @Transactional(readOnly = true)
  public List<NewsSummary> latest(int page, int size) {
    return repository.findLatest(PageRequest.of(page, size));
  }

  /** Deletes stories collected more than [days] days ago. */
  @Transactional
  public int purgeOlderThan(int days) {
    return repository.deleteCollectedBefore(clock.instant().minus(Duration.ofDays(days)));
  }

  static String hash(String url) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.trim().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
