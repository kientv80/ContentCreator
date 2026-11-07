package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.contentcreator.news.NewsSummaryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** Provider gemini: only the collection worker exists (and, with the worker disabled, it never calls out). */
@SpringBootTest(properties = "contentcreator.news.worker.provider=gemini")
class WorkerProviderGeminiTest {
  @Autowired ApplicationContext context;

  @Test
  void providerGemini_hasTheCollectionWorkerAndNotTheClaudeWorker() {
    assertThat(context.getBeansOfType(NewsCollectionWorker.class)).hasSize(1);
    assertThat(context.getBeansOfType(NewsSummaryWorker.class)).isEmpty();
  }
}
