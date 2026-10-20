package com.contentcreator.news;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A row of the news_summary table. */
@Entity
@Table(name = "news_summary")
public class NewsSummary {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 300)
  private String title;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String summary;

  private String category;

  @Column(name = "source_name")
  private String sourceName;

  @Column(name = "source_url", nullable = false, length = 1000)
  private String sourceUrl;

  @Column(name = "url_hash", nullable = false, length = 64, unique = true)
  private String urlHash;

  @Column(nullable = false)
  private String language = "vi";

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "collected_at", nullable = false)
  private Instant collectedAt;

  protected NewsSummary() {}

  public NewsSummary(NewsItem item, String urlHash, Instant collectedAt) {
    this.title = item.title();
    this.summary = item.summary();
    this.category = item.category();
    this.sourceName = item.sourceName();
    this.sourceUrl = item.sourceUrl();
    this.urlHash = urlHash;
    this.publishedAt = item.publishedAt();
    this.collectedAt = collectedAt;
  }

  public Long getId() { return id; }
  public String getTitle() { return title; }
  public String getSummary() { return summary; }
  public String getCategory() { return category; }
  public String getSourceName() { return sourceName; }
  public String getSourceUrl() { return sourceUrl; }
  public String getLanguage() { return language; }
  public Instant getPublishedAt() { return publishedAt; }
  public Instant getCollectedAt() { return collectedAt; }
}
