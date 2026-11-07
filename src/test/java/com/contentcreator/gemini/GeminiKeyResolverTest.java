package com.contentcreator.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The documented test vector below was produced by a small throwaway script (Java, javax.crypto) from FAKE values:
 * master secret = bytes 0x00..0x1f (base64 {@value #MASTER}), IV = bytes 0xa0..0xab, row id "1" as ASCII associated
 * data, plaintext {@value #PLAIN}, AES/GCM/NoPadding with a 128-bit tag appended to the ciphertext.
 * The backend's {@code NewsCollectionKeyCipher} describes the same format; none of these values is a real key.
 */
@SpringBootTest
class GeminiKeyResolverTest {

  static final String MASTER = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  static final String PLAIN = "fake-gemini-key-0123456789abcdef";
  static final String IV_HEX = "a0a1a2a3a4a5a6a7a8a9aaab";
  static final String CT_HEX = "8079174868ac67d20b0beefe6c1fb9f3409d6b23a682745ba43747e41ccf106765f82d527e0a6b00d77fcb45174985a3";
  static final String ENV_KEY = "fake-env-key-abcdefghijklmnop";

  @Autowired JdbcTemplate jdbc;
  ListAppender<ILoggingEvent> logs = new ListAppender<>();
  Logger logger = (Logger) LoggerFactory.getLogger(GeminiKeyResolver.class);

  @BeforeEach
  void setUp() {
    clean();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void clean() {
    logger.detachAppender(logs);
    jdbc.update("DELETE FROM news_collection_secret");
  }

  static GeminiProperties props(String envKey) {
    return new GeminiProperties(envKey, "http://gemini.test", "m", 100, 5);
  }

  void saveRow(String ctHex, String ivHex) {
    jdbc.update("INSERT INTO news_collection_secret (id, ciphertext, iv, last4) VALUES (1, ?, ?, 'cdef')",
        HexFormat.of().parseHex(ctHex), HexFormat.of().parseHex(ivHex));
  }

  String allLogs() {
    return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "|" + b);
  }

  @Test
  void decrypt_opensTheDocumentedTestVector() throws GeneralSecurityException {
    GeminiKeyResolver resolver = new GeminiKeyResolver(jdbc, props(null), MASTER);

    String plain = resolver.decrypt(HexFormat.of().parseHex(CT_HEX), HexFormat.of().parseHex(IV_HEX));

    assertThat(plain).isEqualTo(PLAIN);
  }

  @Test
  void decrypt_failsOnATamperedCiphertextAWrongIvAndAWrongSecret() {
    byte[] tampered = HexFormat.of().parseHex(CT_HEX);
    tampered[0] ^= 1;
    byte[] iv = HexFormat.of().parseHex(IV_HEX);
    byte[] ct = HexFormat.of().parseHex(CT_HEX);
    GeminiKeyResolver good = new GeminiKeyResolver(jdbc, props(null), MASTER);
    GeminiKeyResolver other = new GeminiKeyResolver(jdbc, props(null), java.util.Base64.getEncoder().encodeToString(new byte[32]));

    org.junit.jupiter.api.Assertions.assertThrows(GeneralSecurityException.class, () -> good.decrypt(tampered, iv));
    org.junit.jupiter.api.Assertions.assertThrows(GeneralSecurityException.class, () -> good.decrypt(ct, new byte[12]));
    org.junit.jupiter.api.Assertions.assertThrows(GeneralSecurityException.class, () -> other.decrypt(ct, iv));
  }

  @Test
  void decrypt_withoutAMasterSecretIsRefused() {
    GeminiKeyResolver none = new GeminiKeyResolver(jdbc, props(null), "");

    org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> none.decrypt(new byte[20], new byte[12]));
  }

  @Test
  void resolve_prefersTheSavedKeyOverTheEnvironmentKey() {
    saveRow(CT_HEX, IV_HEX);

    assertThat(new GeminiKeyResolver(jdbc, props(ENV_KEY), MASTER).resolve()).isEqualTo(PLAIN);
    assertThat(allLogs()).isEmpty();
  }

  @Test
  void resolve_usesTheEnvironmentKeyWhenNothingIsSaved_withoutLoggingAnything() {
    assertThat(new GeminiKeyResolver(jdbc, props(ENV_KEY), MASTER).resolve()).isEqualTo(ENV_KEY);
    assertThat(allLogs()).isEmpty();
  }

  @Test
  void resolve_isEmptyWhenThereIsNoKeyAnywhere() {
    assertThat(new GeminiKeyResolver(jdbc, props(null), MASTER).resolve()).isEmpty();
    assertThat(new GeminiKeyResolver(jdbc, props(""), MASTER).resolve()).isEmpty();
  }

  @Test
  void resolve_aSavedRowWithoutAMasterSecretLogsOnlyKeyUnreadable_andFallsBack() {
    saveRow(CT_HEX, IV_HEX);

    assertThat(new GeminiKeyResolver(jdbc, props(ENV_KEY), "").resolve()).isEqualTo(ENV_KEY);
    assertThat(logs.list).hasSize(1);
    assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("news_collect_key_unreadable");
    assertThat(logs.list.get(0).getThrowableProxy()).isNull();
  }

  @Test
  void resolve_aBadMasterSecretIsTreatedAsMissing() {
    saveRow(CT_HEX, IV_HEX);

    assertThat(new GeminiKeyResolver(jdbc, props(ENV_KEY), "not base64 !!").resolve()).isEqualTo(ENV_KEY);
    assertThat(new GeminiKeyResolver(jdbc, props(ENV_KEY), "AAEC").resolve()).isEqualTo(ENV_KEY);
    assertThat(logs.list).hasSize(2);
  }

  @Test
  void resolve_aWrongMasterOrTamperedRowLogsOnlyKeyUnreadable_andNeverTheSecrets() {
    saveRow(CT_HEX.substring(0, 10) + "00" + CT_HEX.substring(12), IV_HEX);

    String resolved = new GeminiKeyResolver(jdbc, props(ENV_KEY), MASTER).resolve();

    assertThat(resolved).isEqualTo(ENV_KEY);
    assertThat(allLogs()).isEqualTo("|news_collect_key_unreadable").doesNotContain(PLAIN).doesNotContain(MASTER).doesNotContain(CT_HEX);
  }

  @Test
  void resolve_aTamperedRowWithNoEnvironmentKeyGivesEmpty() {
    saveRow(CT_HEX.substring(0, 10) + "00" + CT_HEX.substring(12), IV_HEX);

    assertThat(new GeminiKeyResolver(jdbc, props(null), MASTER).resolve()).isEmpty();
  }

  @Test
  void resolve_aDatabaseErrorLogsOnlyKeyUnreadable_withoutTheExceptionMessage() {
    JdbcTemplate broken = mock(JdbcTemplate.class);
    when(broken.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<byte[][]>>any()))
        .thenThrow(new DataAccessResourceFailureException("password=hunter2 url=jdbc:mysql://secret-host"));

    String resolved = new GeminiKeyResolver(broken, props(ENV_KEY), MASTER).resolve();

    assertThat(resolved).isEqualTo(ENV_KEY);
    assertThat(allLogs()).isEqualTo("|news_collect_key_unreadable").doesNotContain("hunter2");
    assertThat(logs.list.get(0).getThrowableProxy()).isNull();
  }
}
