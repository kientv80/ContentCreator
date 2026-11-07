package com.contentcreator.gemini;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds the Gemini API key for one collection run: the key an admin saved on the News collection page first, else the
 * {@code GEMINI_API_KEY} environment key. The saved key lives in {@code news_collection_secret} (ElderEase V63) as
 * AES-256-GCM ciphertext: master secret from the environment value {@code NEWS_COLLECTION_SECRET_KEY} (base64 of 32 bytes),
 * 12-byte IV from the table, the row id as ASCII text ({@code "1"}) as associated data, and the 128-bit GCM tag appended
 * to the ciphertext. This is an independent implementation of that documented format (see the test vector in
 * {@code GeminiKeyResolverTest}), not shared code with the backend.
 * The key, the ciphertext, the IV and the master secret are never logged, printed or put in an exception, and this class
 * has no {@code toString} that shows any of them.
 */
@Component
public class GeminiKeyResolver {

  private static final Logger log = LoggerFactory.getLogger(GeminiKeyResolver.class);
  private static final int MASTER_BYTES = 32;
  private static final int TAG_BITS = 128;
  private static final int ROW_ID = 1;

  private final JdbcTemplate jdbc;
  private final GeminiProperties props;
  private final SecretKeySpec masterKey;

  /**
   * @param jdbc the shared JDBC template (needs SELECT on {@code news_collection_secret})
   * @param props the Gemini settings (the environment key is the fallback)
   * @param masterSecretBase64 {@code NEWS_COLLECTION_SECRET_KEY}: base64 of 32 bytes, or empty when not configured
   */
  @Autowired
  public GeminiKeyResolver(JdbcTemplate jdbc, GeminiProperties props, @Value("${NEWS_COLLECTION_SECRET_KEY:}") String masterSecretBase64) {
    this.jdbc = jdbc;
    this.props = props;
    this.masterKey = parseMaster(masterSecretBase64);
  }

  /**
   * Decodes the master secret.
   *
   * @param base64 the configured text, possibly empty
   * @return the AES key, or {@code null} when absent, not base64 or not 32 bytes
   */
  private static SecretKeySpec parseMaster(String base64) {
    if (base64 == null || base64.isBlank()) {
      return null;
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(base64.trim());
      return bytes.length == MASTER_BYTES ? new SecretKeySpec(bytes, "AES") : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Resolves the key once for a run: the saved key when one exists and can be read, else the environment key.
   * When a saved row exists but cannot be used (no master secret, a wrong secret, tampering, a database error) it logs
   * {@code news_collect_key_unreadable} (no detail) and uses the environment key instead.
   *
   * @return the key to use, or an empty string when neither exists (the caller records {@code KEY_MISSING})
   */
  public String resolve() {
    String saved = savedKey();
    if (saved != null && !saved.isBlank()) {
      return saved;
    }
    return props.apiKey() == null ? "" : props.apiKey();
  }

  /**
   * Reads and decrypts the saved key.
   *
   * @return the plain key, or {@code null} when there is no saved row or it cannot be used (logged without detail)
   */
  private String savedKey() {
    try {
      List<byte[][]> rows = jdbc.query(
          "SELECT ciphertext, iv FROM news_collection_secret WHERE id = 1",
          (rs, i) -> new byte[][] {rs.getBytes("ciphertext"), rs.getBytes("iv")});
      if (rows.isEmpty()) {
        return null;
      }
      return decrypt(rows.get(0)[0], rows.get(0)[1]);
    } catch (RuntimeException | GeneralSecurityException e) {
      log.warn("news_collect_key_unreadable");
      return null;
    }
  }

  /**
   * AES-256-GCM decryption with the row id as associated data.
   *
   * @param ciphertext the stored ciphertext with the tag appended
   * @param iv the stored 12-byte IV
   * @return the plain key
   * @throws GeneralSecurityException on a wrong secret, wrong IV or tampering
   * @throws IllegalStateException when no master secret is configured
   */
  String decrypt(byte[] ciphertext, byte[] iv) throws GeneralSecurityException {
    if (masterKey == null) {
      throw new IllegalStateException("no master secret");
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
    cipher.updateAAD(Integer.toString(ROW_ID).getBytes(StandardCharsets.US_ASCII));
    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
  }
}
