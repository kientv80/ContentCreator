package com.contentcreator.news;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a bulletin story's {@code published} value into the short Vietnam-time text shown in the story byline
 * (FR-321): {@code HH:mm dd/MM}, or only {@code dd/MM} when the value has no time of day.
 */
final class PublishedTime {

  private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
  private static final DateTimeFormatter WITH_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM");
  private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("dd/MM");
  /** Leading {@code yyyy-MM-dd[Thh:mm[:ss][Z|+hh:mm|+hhmm]]}; whatever follows is ignored. */
  private static final Pattern LEADING = Pattern.compile(
      "^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?(Z|[+-]\\d{2}:?\\d{2})?)?");

  private PublishedTime() {}

  /**
   * Formats a published value for the byline.
   *
   * @param value the raw {@code published} text; may be {@code null}, blank or garbage
   * @return {@code HH:mm dd/MM} in Vietnam time; {@code dd/MM} for a date only or a midnight placeholder; an empty
   *     string when the value is missing or cannot be read (invalid calendar or clock values included)
   */
  static String format(String value) {
    if (value == null) {
      return "";
    }
    Matcher m = LEADING.matcher(value.trim());
    if (!m.find()) {
      return "";
    }
    try {
      LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
      if (m.group(4) == null) {
        return DATE_ONLY.format(date);
      }
      LocalDateTime at = date.atTime(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
          m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
      if (m.group(7) != null) {
        at = at.atOffset(ZoneOffset.of(m.group(7))).atZoneSameInstant(VIETNAM).toLocalDateTime();
      }
      return at.toLocalTime().equals(LocalTime.MIDNIGHT) ? DATE_ONLY.format(at) : WITH_TIME.format(at);
    } catch (DateTimeException e) {
      return "";
    }
  }
}
