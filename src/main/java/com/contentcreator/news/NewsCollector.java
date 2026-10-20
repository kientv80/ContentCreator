package com.contentcreator.news;

import java.util.List;

/** Finds today's Vietnamese news and summarises it. */
public interface NewsCollector {

  /**
   * @param maxItems the most stories to return
   * @return the stories, never {@code null}; empty when nothing usable was found
   */
  List<NewsItem> collect(int maxItems);
}
