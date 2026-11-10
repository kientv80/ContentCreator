package com.contentcreator.news.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.contentcreator.thoughts.ThoughtProperties;
import com.contentcreator.thoughts.ThoughtRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class LetterIntakeServiceTest {

  private static final String LETTER = "# Thu\\n\\n## Bài 1: Một\\n\\nNội dung một.\\n\\n## Bài 2: Hai\\n\\nNội dung hai.";

  @Autowired JdbcTemplate jdbc;
  private LetterIntakeService service;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM poems");
    jdbc.update("DELETE FROM users");
    jdbc.update("INSERT INTO users (id, username) VALUES ('vn-id', 'fake_vn'), ('us-id', 'fake_us')");
    var props = new ThoughtProperties(false, "x", 60, 0, 1000, new ThoughtProperties.Accounts("fake_vn", "fake_us"));
    Clock clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
    service = new LetterIntakeService(props, new ThoughtRepository(jdbc), clock);
  }

  private LetterIntakeService.Result run(String json) throws Exception {
    return service.process(new ObjectMapper().readTree(json));
  }

  @Test
  void storesTwoPostsNewestFirstWithTodayAsDefaultDate() throws Exception {
    var r = run("{\"letter\":\"" + LETTER + "\"}");

    assertThat(r.status()).isEqualTo(LetterIntakeService.Status.CREATED);
    assertThat(r.received()).isEqualTo(2);
    assertThat(r.stored()).isEqualTo(2);
    List<String> order = jdbc.queryForList("SELECT content_text FROM poems ORDER BY created_at DESC", String.class);
    assertThat(order.get(0)).startsWith("Bài 1");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poems WHERE local_date = DATE '2026-11-10' AND writer_id = 'vn-id'", Integer.class)).isEqualTo(2);
  }

  @Test
  void theSameLetterAgainIsAllDuplicates() throws Exception {
    run("{\"letter\":\"" + LETTER + "\"}");
    var r = run("{\"letter\":\"" + LETTER + "\"}");

    assertThat(r.status()).isEqualTo(LetterIntakeService.Status.ALREADY_STORED);
    assertThat(r.stored()).isZero();
    assertThat(r.skipped()).isEqualTo(2);
    assertThat(r.duplicates()).isEqualTo(2);
  }

  @Test
  void anEnglishLetterUsesTheUsAccountAndTheGivenDate() throws Exception {
    var r = run("{\"language\":\"en\",\"date\":\"2026-11-01\",\"letter\":\"## One\\n\\nBody.\"}");

    assertThat(r.status()).isEqualTo(LetterIntakeService.Status.CREATED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM poems WHERE writer_id = 'us-id' AND local_date = DATE '2026-11-01'", Integer.class)).isEqualTo(1);
  }

  @Test
  void anotherLanguageIsInvalid() throws Exception {
    assertThat(run("{\"language\":\"fr\",\"letter\":\"## A\\n\\nB\"}").status()).isEqualTo(LetterIntakeService.Status.INVALID_LANGUAGE);
    assertThat(run("{\"language\":5,\"letter\":\"## A\\n\\nB\"}").status()).isEqualTo(LetterIntakeService.Status.INVALID_LANGUAGE);
  }

  @Test
  void aBadDateIsAnInvalidBody() throws Exception {
    assertThat(run("{\"date\":\"2026-02-30\",\"letter\":\"## A\\n\\nB\"}").status()).isEqualTo(LetterIntakeService.Status.INVALID_BODY);
    assertThat(run("{\"date\":20261101,\"letter\":\"## A\\n\\nB\"}").status()).isEqualTo(LetterIntakeService.Status.INVALID_BODY);
  }

  @Test
  void aMissingWriterAccountIsReported() throws Exception {
    jdbc.update("DELETE FROM users WHERE id = 'us-id'");
    assertThat(run("{\"language\":\"en\",\"letter\":\"## A\\n\\nB\"}").status()).isEqualTo(LetterIntakeService.Status.WRITER_MISSING);
  }

  @Test
  void aLetterWithNoUsableTextHasNoValidPost() throws Exception {
    assertThat(run("{\"letter\":\"   \"}").status()).isEqualTo(LetterIntakeService.Status.NO_VALID_POST);
  }
}
