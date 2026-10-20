package com.contentcreator.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.contentcreator.news.NewsDraft;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClaudeNewsCollectorTest {

  private static ClaudeProperties props(String key) {
    return new ClaudeProperties(key, "http://claude.test", "test-model", 100, 3, "web_search_20250305", 5);
  }

  private final RestClient.Builder builder = RestClient.builder().baseUrl("http://claude.test");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

  @Test
  void sendsTheModelWebSearchToolAndKeyAndReadsTheFinalTextBlocks() {
    server.expect(requestTo("http://claude.test/v1/messages"))
        .andExpect(header("x-api-key", "k"))
        .andExpect(header("anthropic-version", "2023-06-01"))
        .andExpect(jsonPath("$.model").value("test-model"))
        .andExpect(jsonPath("$.tools[0].type").value("web_search_20250305"))
        .andExpect(jsonPath("$.tools[0].max_uses").value(3))
        .andRespond(withSuccess("{\"content\":[{\"type\":\"server_tool_use\"},{\"type\":\"text\",\"text\":\"Searching\"},"
            + "{\"type\":\"text\",\"text\":\"[{\\\"headline\\\":\\\"A\\\",\\\"summary\\\":\\\"s\\\",\\\"newsDate\\\":\\\"2026-10-20\\\"}]\"}]}",
            MediaType.APPLICATION_JSON));

    List<NewsDraft> drafts = new ClaudeNewsCollector(props("k"), builder.build()).collect(5);

    assertThat(drafts).extracting(NewsDraft::headline).containsExactly("A");
    server.verify();
  }

  @Test
  void refusesWithoutAnApiKey() {
    assertThatThrownBy(() -> new ClaudeNewsCollector(props(" "), builder.build()).collect(5)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new ClaudeNewsCollector(props(null), builder.build()).collect(5)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void anErrorOrAnAnswerWithoutTextPropagates() {
    server.expect(requestTo("http://claude.test/v1/messages")).andRespond(withServerError());
    assertThatThrownBy(() -> new ClaudeNewsCollector(props("k"), builder.build()).collect(5)).isInstanceOf(RuntimeException.class);

    server.reset();
    server.expect(requestTo("http://claude.test/v1/messages")).andRespond(withSuccess("{\"content\":[]}", MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> new ClaudeNewsCollector(props("k"), builder.build()).collect(5)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theProductionConstructorBuildsAClientFromTheBuilder() {
    assertThat(new ClaudeNewsCollector(props("k"), RestClient.builder())).isNotNull();
  }
}
