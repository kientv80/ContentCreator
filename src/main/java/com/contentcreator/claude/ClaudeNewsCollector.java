package com.contentcreator.claude;

import com.contentcreator.news.NewsCollector;
import com.contentcreator.news.NewsItem;
import com.contentcreator.news.NewsJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Asks Claude, with the web search tool, for today's Vietnamese news summarised in Vietnamese. */
@Component
public class ClaudeNewsCollector implements NewsCollector {

  private final ClaudeProperties props;
  private final RestClient client;

  public ClaudeNewsCollector(ClaudeProperties props, RestClient.Builder builder) {
    this.props = props;
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
    this.client = builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
  }

  @Override
  public List<NewsItem> collect(int maxItems) {
    if (props.apiKey() == null || props.apiKey().isBlank()) {
      throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
    }
    String today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).toString();
    Map<String, Object> body = Map.of(
        "model", props.model(),
        "max_tokens", props.maxTokens(),
        "tools", List.of(Map.of("type", props.webSearchToolType(), "name", "web_search", "max_uses", props.webSearchMaxUses())),
        "messages", List.of(Map.of("role", "user", "content", prompt(today, maxItems))));
    JsonNode response = client.post()
        .uri("/v1/messages")
        .header("x-api-key", props.apiKey())
        .header("anthropic-version", "2023-06-01")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(JsonNode.class);
    return NewsJsonParser.parse(answerText(response));
  }

  /** The text blocks after the last web search block hold the final answer. */
  static String answerText(JsonNode response) {
    StringBuilder text = new StringBuilder();
    if (response != null && response.has("content")) {
      for (JsonNode block : response.get("content")) {
        if ("text".equals(block.path("type").asText())) {
          text.append(block.path("text").asText()).append('\n');
        }
      }
    }
    return text.toString();
  }

  static String prompt(String today, int maxItems) {
    return """
        Today is %s (Vietnam time). Use web search to find the most important news in Vietnam from today or the last 24 hours, \
        from reliable Vietnamese outlets (for example VnExpress, Tuoi Tre, Thanh Nien, Dan Tri, VTV, Nhan Dan). \
        Cover a mix of topics useful to older adults: health, society, weather, economy, and national events. \
        Skip celebrity gossip, crime scene details and anything graphic.

        Write each summary in Vietnamese, in 2 to 3 plain sentences, in your own words (no copied text). \
        Return at most %d stories. Reply with ONLY a JSON array, no other text, where each element is:
        {"title": "Vietnamese headline", "summary": "...", "category": "one of: health, society, weather, economy, national, world, other", \
        "source": "outlet name", "url": "the article link", "published": "ISO-8601 date or datetime if known"}
        """.formatted(today, maxItems);
  }
}
