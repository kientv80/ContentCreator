package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NewsIntakeDisabledTest {

  @Autowired MockMvc mvc;
  @Autowired ApplicationContext context;

  @Test
  void withNoConfiguredHashTheRouteDoesNotExistAndAnswers404EvenWithAToken() throws Exception {
    mvc.perform(post("/api/v1/news-bulletins").header("Authorization", "Bearer fake-intake-token-0000").content("{}"))
        .andExpect(status().isNotFound());

    assertThat(context.getBeanNamesForType(NewsIntakeController.class)).isEmpty();
    assertThat(context.getBeanNamesForType(IntakeAuthFilter.class)).isEmpty();
  }
}
