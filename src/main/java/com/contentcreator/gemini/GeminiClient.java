package com.contentcreator.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * One Google Gemini {@code generateContent} call with Google Search grounding. The key travels only in the
 * {@code x-goog-api-key} header; it is never put in the URL, a log line or an exception message.
 */
@Component
public class GeminiClient {

  private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
  private static final Pattern GOOGLE_STATUS = Pattern.compile("^[A-Z_]{3,40}$");

  private final GeminiProperties props;
  private final RestClient client;

  /**
   * @param props the Gemini settings (base URL, model, token limit, timeout, key)
   * @param builder the shared Spring client builder
   */
  @Autowired
  public GeminiClient(GeminiProperties props, RestClient.Builder builder) {
    this(props, build(props, builder));
  }

  /** Uses a ready client (tests). */
  GeminiClient(GeminiProperties props, RestClient client) {
    this.props = props;
    this.client = client;
  }

  /** Builds the HTTP client with the configured base URL and read timeout. */
  private static RestClient build(GeminiProperties props, RestClient.Builder builder) {
    var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
    return builder.baseUrl(props.baseUrl()).requestFactory(factory).build();
  }

  /**
   * Sends the prompt to Gemini with the Google Search tool, using the configured key.
   *
   * @param prompt the full prompt text
   * @return the answer text and grounding domains, or {@link GeminiResult#missingKey()} without calling Google when no key is set
   * @throws GeminiException on an HTTP error, a timeout or an unreachable server (status text only)
   */
  public GeminiResult generate(String prompt) {
    return generate(prompt, props.apiKey());
  }

  /**
   * Sends the prompt using an explicitly supplied key (the later key-resolution step passes the saved key here).
   *
   * @param prompt the full prompt text
   * @param apiKey the key to use, blank means "missing"
   * @return as {@link #generate(String)}
   * @throws GeminiException on an HTTP error, a timeout or an unreachable server (status text only)
   */
  public GeminiResult generate(String prompt, String apiKey) {
    if (apiKey == null || apiKey.isBlank()) {
      return GeminiResult.missingKey();
    }
    Map<String, Object> body = Map.of(
        "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
        "tools", List.of(Map.of("google_search", Map.of())),
        "generationConfig", Map.of("maxOutputTokens", props.maxTokens()));
    JsonNode response;
    try {
      response = client.post()
          .uri("/v1beta/models/{model}:generateContent", props.model())
          .header("x-goog-api-key", apiKey)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .onStatus(HttpStatusCode::isError, (request, res) -> {
            throw new GeminiException(res.getStatusCode().value(), googleStatus(res.getBody()));
          })
          .body(JsonNode.class);
    } catch (ResourceAccessException e) {
      throw new GeminiException(0, isTimeout(e) ? "TIMEOUT" : "UNREACHABLE");
    }
    GeminiResult result = new GeminiResult(answerText(response), groundingDomains(response), false);
    log.info("gemini_call model={} chars={} groundingDomains={}", props.model(), result.text().length(), result.groundingDomains().size());
    return result;
  }

  /** Reads Google's {@code error.status} (like RESOURCE_EXHAUSTED) from an error body; "UNKNOWN" when absent or odd-looking. */
  private static String googleStatus(java.io.InputStream body) {
    try {
      String status = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("error").path("status").asText("");
      return GOOGLE_STATUS.matcher(status).matches() ? status : "UNKNOWN";
    } catch (IOException | RuntimeException e) {
      return "UNKNOWN";
    }
  }

  /** Whether a transport failure was a connect or read timeout. */
  private static boolean isTimeout(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
        return true;
      }
    }
    return false;
  }

  /** Joins the text parts of the first candidate (grounded answers can arrive in several parts). */
  static String answerText(JsonNode response) {
    StringBuilder text = new StringBuilder();
    if (response != null) {
      for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
        if (part.hasNonNull("text")) {
          text.append(part.get("text").asText()).append('\n');
        }
      }
    }
    return text.toString();
  }

  /** The distinct lower-case {@code web.title} values (publisher domains) of the first candidate's grounding chunks. */
  static List<String> groundingDomains(JsonNode response) {
    Set<String> domains = new LinkedHashSet<>();
    if (response != null) {
      for (JsonNode chunk : response.path("candidates").path(0).path("groundingMetadata").path("groundingChunks")) {
        String title = chunk.path("web").path("title").asText("").trim().toLowerCase(Locale.ROOT);
        if (!title.isEmpty()) {
          domains.add(title);
        }
      }
    }
    return List.copyOf(domains);
  }
}
