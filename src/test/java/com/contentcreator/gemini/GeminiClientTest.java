package com.contentcreator.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiClientTest {

  private static final String URL = "http://gemini.test/v1beta/models/test-model:generateContent";
  private static final String KEY = "fake-key-1234567890";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String GROUNDED = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"part one\"},{\"x\":1},{\"text\":\"part two\"}]},"
      + "\"groundingMetadata\":{\"groundingChunks\":[{\"web\":{\"uri\":\"https://redirect.invalid/a\",\"title\":\"VnExpress.NET\"}},"
      + "{\"web\":{\"title\":\"vnexpress.net\"}},{\"web\":{\"title\":\" \"}},{\"other\":{}},{\"web\":{\"title\":\"tuoitre.vn\"}}]}}]}";

  private static GeminiProperties props(String key) {
    return new GeminiProperties(key, "http://gemini.test", "test-model", 100, 5);
  }

  private final RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

  private GeminiClient client(String key) {
    return new GeminiClient(props(key), builder.build());
  }

  @Test
  void generateSendsKeyHeaderSearchToolAndTokenLimitAndReturnsTextAndDomains() {
    server.expect(requestTo(URL))
        .andExpect(header("x-goog-api-key", KEY))
        .andExpect(jsonPath("$.tools[0].google_search").exists())
        .andExpect(jsonPath("$.generationConfig.maxOutputTokens").value(100))
        .andExpect(jsonPath("$.contents[0].parts[0].text").value("the prompt"))
        .andRespond(withSuccess(GROUNDED, MediaType.APPLICATION_JSON));

    GeminiResult result = client(KEY).generate("the prompt");

    assertThat(result.text()).isEqualTo("part one\npart two\n");
    assertThat(result.groundingDomains()).containsExactly("vnexpress.net", "tuoitre.vn");
    assertThat(result.keyMissing()).isFalse();
    server.verify();
  }

  @Test
  void generateWithAnExplicitKeyUsesThatKey() {
    server.expect(requestTo(URL)).andExpect(header("x-goog-api-key", "other-fake-key")).andRespond(withSuccess(GROUNDED, MediaType.APPLICATION_JSON));

    assertThat(client(KEY).generate("p", "other-fake-key").text()).isNotEmpty();
    server.verify();
  }

  @Test
  void generateWithoutAKeyReturnsKeyMissingAndDoesNotCallGoogle() {
    assertThat(client(" ").generate("p").keyMissing()).isTrue();
    assertThat(client(null).generate("p", null).keyMissing()).isTrue();
    assertThat(client(null).generate("p").text()).isEmpty();
    server.verify(); // no request was expected, none was made
  }

  @Test
  void generateWithoutGroundingMetadataReturnsNoDomains() {
    server.expect(requestTo(URL)).andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"t\"}]}}]}", MediaType.APPLICATION_JSON));

    GeminiResult result = client(KEY).generate("p");

    assertThat(result.text()).isEqualTo("t\n");
    assertThat(result.groundingDomains()).isEmpty();
  }

  @Test
  void generateWithAnEmptyBodyReturnsEmptyText() {
    server.expect(requestTo(URL)).andRespond(withSuccess());

    assertThat(client(KEY).generate("p").text()).isEmpty();
  }

  @Test
  void generateTurnsAnHttpErrorIntoAnExceptionWithStatusAndGoogleStatusOnly() {
    String body = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded for key " + KEY + "\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(body).contentType(MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client(KEY).generate("p"))
        .isInstanceOfSatisfying(GeminiException.class, e -> {
          assertThat(e.httpStatus()).isEqualTo(429);
          assertThat(e.googleStatus()).isEqualTo("RESOURCE_EXHAUSTED");
          assertThat(e.getMessage()).isEqualTo("Gemini call failed: HTTP 429 RESOURCE_EXHAUSTED").doesNotContain(KEY);
        });
  }

  @Test
  void generateReportsUnknownWhenTheErrorBodyIsMissingOrOdd() {
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("<html>oops</html>"));
    assertThatThrownBy(() -> client(KEY).generate("p")).isInstanceOfSatisfying(GeminiException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Gemini call failed: HTTP 500 UNKNOWN"));

    server.reset();
    server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
        .body("{\"error\":{\"status\":\"see " + KEY + "\"}}").contentType(MediaType.APPLICATION_JSON));
    assertThatThrownBy(() -> client(KEY).generate("p")).isInstanceOfSatisfying(GeminiException.class,
        e -> assertThat(e.getMessage()).isEqualTo("Gemini call failed: HTTP 400 UNKNOWN").doesNotContain(KEY));
  }

  @Test
  void generateReportsATimeoutAndAnUnreachableServerWithFixedCodes() {
    server.expect(requestTo(URL)).andRespond(request -> {
      throw new SocketTimeoutException("read timed out for " + KEY);
    });
    assertThatThrownBy(() -> client(KEY).generate("p")).isInstanceOfSatisfying(GeminiException.class, e -> {
      assertThat(e.getMessage()).isEqualTo("Gemini call failed: HTTP 0 TIMEOUT").doesNotContain(KEY);
    });

    server.reset();
    server.expect(requestTo(URL)).andRespond(request -> {
      throw new HttpTimeoutException("request timed out");
    });
    assertThatThrownBy(() -> client(KEY).generate("p")).isInstanceOfSatisfying(GeminiException.class,
        e -> assertThat(e.googleStatus()).isEqualTo("TIMEOUT"));

    server.reset();
    server.expect(requestTo(URL)).andRespond(request -> {
      throw new java.net.ConnectException("refused");
    });
    assertThatThrownBy(() -> client(KEY).generate("p")).isInstanceOfSatisfying(GeminiException.class,
        e -> assertThat(e.googleStatus()).isEqualTo("UNREACHABLE"));
  }

  @Test
  void generateWritesNoLogLineContainingTheKey() {
    Logger logger = (Logger) LoggerFactory.getLogger(GeminiClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      server.expect(requestTo(URL)).andRespond(withSuccess(GROUNDED, MediaType.APPLICATION_JSON));
      client(KEY).generate("p");
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list).isNotEmpty().allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(KEY));
    assertThat(appender.list.get(0).getFormattedMessage()).contains("gemini_call", "test-model");
  }

  @Test
  void answerTextHandlesNullAndPartsWithoutText() throws Exception {
    assertThat(GeminiClient.answerText(null)).isEmpty();
    assertThat(GeminiClient.answerText(JSON.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"x\":1},{\"text\":\"a\"},{\"text\":\"b\"}]}}]}")))
        .isEqualTo("a\nb\n");
  }

  @Test
  void groundingDomainsLowercasesDeduplicatesAndSkipsBlankTitles() throws Exception {
    assertThat(GeminiClient.groundingDomains(null)).isEmpty();
    assertThat(GeminiClient.groundingDomains(JSON.readTree(GROUNDED))).containsExactly("vnexpress.net", "tuoitre.vn");
  }

  @Test
  void resultKeepsDefensiveCopiesAndDefaults() {
    GeminiResult r = new GeminiResult(null, null, false);
    assertThat(r.text()).isEmpty();
    assertThat(r.groundingDomains()).isEmpty();
    assertThat(GeminiResult.missingKey().keyMissing()).isTrue();
    assertThat(new GeminiResult("t", List.of("a.com"), false).groundingDomains()).containsExactly("a.com");
  }

  @Test
  void propertiesNeverPrintTheKey() {
    assertThat(props("secret").toString()).doesNotContain("secret").contains("<hidden>");
    assertThat(props(null).toString()).contains("<unset>");
    assertThat(props(" ").toString()).contains("<unset>");
  }

  @Test
  void theSpringConstructorBuildsAClientFromTheProperties() {
    assertThat(new GeminiClient(props(null), RestClient.builder()).generate("p").keyMissing()).isTrue();
  }
}
