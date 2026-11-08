package com.contentcreator.news.intake;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards {@code POST /api/v1/news-bulletins}: counts the attempt against the client's rate limit (failed attempts
 * count too), then checks {@code Authorization: Bearer <token>} by comparing the token's SHA-256 with the configured
 * hash in constant time. Runs before the body is read. A failure answers 401 with an empty body and logs only the address.
 */
@Component
@ConditionalOnExpression("!'${contentcreator.news.intake.token-sha256:}'.isBlank()")
public class IntakeAuthFilter extends OncePerRequestFilter {

  /** The one path this filter protects. */
  public static final String PATH = "/api/v1/news-bulletins";
  private static final Logger log = LoggerFactory.getLogger(IntakeAuthFilter.class);

  private final byte[] expected;
  private final boolean trustProxy;
  private final IntakeRateLimiter limiter;

  /**
   * @param props supplies the hash and the proxy setting
   * @param limiter the per-address counter
   * @throws IllegalStateException when the configured hash is not 64 hex characters (the value is not echoed)
   */
  public IntakeAuthFilter(NewsIntakeProperties props, IntakeRateLimiter limiter) {
    String hex = props.tokenSha256().trim();
    if (!hex.matches("[0-9a-fA-F]{64}")) {
      throw new IllegalStateException("contentcreator.news.intake.token-sha256 must be 64 hex characters");
    }
    this.expected = HexFormat.of().parseHex(hex);
    this.trustProxy = props.trustProxy();
    this.limiter = limiter;
  }

  /** Only the intake path is filtered; every other request passes untouched. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PATH.equals(request.getRequestURI());
  }

  /** Applies the rate limit, then the token check; continues the chain only for a valid token within the limit. */
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String ip = clientAddress(request);
    long retryAfter = limiter.tryAcquire(ip);
    if (retryAfter >= 0) {
      response.setStatus(429);
      response.setHeader("Retry-After", Long.toString(retryAfter));
      log.info("news_intake_rejected reason=rate_limited ip={}", ip);
      return;
    }
    if (!tokenMatches(request.getHeader("Authorization"))) {
      log.info("news_intake_unauthorized ip={} reason={}", ip, rejectReason(request.getHeader("Authorization")));
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * Why a request was refused, for the log only: {@code no_header}, {@code not_bearer}, {@code empty_token} or
   * {@code wrong_token len=N} (the length of the value sent, never the value itself).
   *
   * @param header the Authorization header, may be null
   * @return a short reason without any part of the token
   */
  static String rejectReason(String header) {
    if (header == null || header.isBlank()) {
      return "no_header";
    }
    if (header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return "not_bearer";
    }
    String token = header.substring(7).trim();
    return token.isEmpty() ? "empty_token" : "wrong_token len=" + token.length();
  }

  /**
   * Constant-time check of a Bearer header against the configured hash.
   *
   * @param header the Authorization header, may be null
   * @return true only for {@code Bearer <token>} whose SHA-256 equals the configured hash
   */
  boolean tokenMatches(String header) {
    if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return false;
    }
    String token = header.substring(7).trim();
    if (token.isEmpty()) {
      return false;
    }
    return MessageDigest.isEqual(sha256(token), expected);
  }

  /**
   * The client address used for the rate limit and logs: the connection address, or with trust-proxy the right-most
   * {@code X-Forwarded-For} value (the one the edge added). Anything that is not an IP-looking text becomes "unknown".
   *
   * @param request the request
   * @return a short, log-safe address
   */
  String clientAddress(HttpServletRequest request) {
    String address = request.getRemoteAddr();
    String forwarded = trustProxy ? request.getHeader("X-Forwarded-For") : null;
    if (forwarded != null && !forwarded.isBlank()) {
      String[] parts = forwarded.split(",");
      address = parts[parts.length - 1].trim();
    }
    if (address == null || !address.toLowerCase(Locale.ROOT).matches("[0-9a-f:.]{1,45}")) {
      return "unknown";
    }
    return address;
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // EXCL: every JRE ships SHA-256
    }
  }
}
