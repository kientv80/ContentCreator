package com.contentcreator.news.collect;

import static org.assertj.core.api.Assertions.assertThat;

import com.contentcreator.news.NewsSummaryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The default test configuration selects the claude provider: only the Claude worker exists. */
@SpringBootTest
class WorkerProviderClaudeTest {
  @Autowired ApplicationContext context;

  @Test
  void providerClaude_hasTheClaudeWorkerAndNotTheCollectionWorker() {
    assertThat(context.getBeansOfType(NewsSummaryWorker.class)).hasSize(1);
    assertThat(context.getBeansOfType(NewsCollectionWorker.class)).isEmpty();
  }
}
