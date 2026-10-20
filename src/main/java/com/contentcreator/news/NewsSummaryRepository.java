package com.contentcreator.news;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NewsSummaryRepository extends JpaRepository<NewsSummary, Long> {

  @Query("select n.urlHash from NewsSummary n where n.urlHash in :hashes")
  List<String> findExistingHashes(Collection<String> hashes);

  @Query("select n from NewsSummary n order by coalesce(n.publishedAt, n.collectedAt) desc, n.id desc")
  List<NewsSummary> findLatest(Pageable pageable);

  @Modifying
  @Query("delete from NewsSummary n where n.collectedAt < :cutoff")
  int deleteCollectedBefore(Instant cutoff);
}
