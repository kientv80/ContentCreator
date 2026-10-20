package com.contentcreator.news;

import java.util.List;

/** Finds today's Vietnamese news and summarises it. */
public interface NewsCollector {

  /**
   * @param maxItems the most stories to return
   * @return the drafts, never {@code null}
   */
  List<NewsDraft> collect(int maxItems);
}
