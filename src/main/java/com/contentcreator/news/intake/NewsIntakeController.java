package com.contentcreator.news.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/news-bulletins}: the scheduled Claude task posts the daily bulletin here. The route exists only
 * when an intake token hash is configured (otherwise Spring answers 404). Authentication and rate limiting happen
 * earlier, in {@link IntakeAuthFilter}.
 */
@RestController
@ConditionalOnExpression("!'${contentcreator.news.intake.token-sha256:}'.isBlank()")
public class NewsIntakeController {

  private static final Logger log = LoggerFactory.getLogger(NewsIntakeController.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final NewsIntakeService service;
  private final int maxBytes;

  /**
   * @param service stores the bulletin
   * @param props supplies the body limit
   */
  public NewsIntakeController(NewsIntakeService service, NewsIntakeProperties props) {
    this.service = service;
    this.maxBytes = props.maxBytes();
  }

  /**
   * Reads at most {@code maxBytes} of the body, parses it as a JSON object and stores its stories.
   *
   * @param request the request (the body is read here, bounded)
   * @return 201 with the counts, 200 when everything was stored before, 400 (not an object, unknown country, bad
   *     language), 413 (too large) or 422 {@code no_valid_story}
   * @throws IOException when the body cannot be read
   */
  @PostMapping(value = "/api/v1/news-bulletins", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws IOException {
    if (request.getContentLengthLong() > maxBytes) {
      return tooLarge();
    }
    byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
    if (body.length > maxBytes) {
      return tooLarge();
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (IOException e) {
      root = null;
    }
    if (root == null || !root.isObject()) {
      log.info("news_intake_rejected reason=not_a_json_object");
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_body", "message", "The body must be a JSON object."));
    }
    NewsIntakeService.Result r = service.process(root);
    return switch (r.status()) {
      case UNKNOWN_COUNTRY -> ResponseEntity.badRequest().body(Map.of("error", "unknown_country", "message", "Unknown country."));
      case INVALID_LANGUAGE -> ResponseEntity.badRequest().body(Map.of("error", "invalid_language", "message", "Unknown language."));
      case NO_VALID_STORY -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "no_valid_story"));
      case CREATED -> ResponseEntity.status(HttpStatus.CREATED).body(counts(r));
      case ALREADY_STORED -> ResponseEntity.ok(counts(r));
    };
  }

  private ResponseEntity<Map<String, Object>> tooLarge() {
    log.info("news_intake_rejected reason=too_large");
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "too_large"));
  }

  private static Map<String, Object> counts(NewsIntakeService.Result r) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("received", r.received());
    out.put("stored", r.stored());
    out.put("skipped", r.skipped());
    List<Map<String, Object>> rejected = r.rejected().stream()
        .<Map<String, Object>>map(x -> Map.of("reason", x.reason(), "count", x.count())).toList();
    out.put("rejected", rejected);
    return out;
  }
}
