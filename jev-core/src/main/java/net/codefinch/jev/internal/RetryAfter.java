package net.codefinch.jev.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the server-suggested retry delay, preferring {@code retry-after-ms} (milliseconds) over
 * {@code retry-after} (seconds, or an HTTP-date). Negative and non-finite values are ignored; a
 * past date yields zero. Matches both upstream SDKs.
 */
public final class RetryAfter {

  /** Millisecond delay header, preferred. */
  public static final String RETRY_AFTER_MS_HEADER = "retry-after-ms";

  /** Standard delay header: seconds or HTTP-date. */
  public static final String RETRY_AFTER_HEADER = "retry-after";

  private RetryAfter() {}

  /** Parses the delay from response headers using the system clock for HTTP-dates. */
  public static Optional<Duration> parse(Map<String, List<String>> headers) {
    return parse(headers, Clock.systemUTC());
  }

  /** Parses the delay from response headers, resolving HTTP-dates against {@code clock}. */
  public static Optional<Duration> parse(Map<String, List<String>> headers, Clock clock) {
    String ms = first(headers, RETRY_AFTER_MS_HEADER);
    if (ms != null) {
      Optional<Duration> parsed = millis(ms, 1);
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    String seconds = first(headers, RETRY_AFTER_HEADER);
    if (seconds == null) {
      return Optional.empty();
    }
    Optional<Duration> numeric = millis(seconds, 1000);
    if (numeric.isPresent()) {
      return numeric;
    }
    return httpDate(seconds, clock);
  }

  private static Optional<Duration> millis(String raw, int multiplier) {
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return Optional.of(Duration.ZERO);
    }
    double value;
    try {
      value = Double.parseDouble(trimmed);
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
    if (!Double.isFinite(value) || value < 0) {
      return Optional.empty();
    }
    double ms = value * multiplier;
    if (!Double.isFinite(ms)) {
      return Optional.empty();
    }
    return Optional.of(Duration.ofMillis((long) ms));
  }

  private static Optional<Duration> httpDate(String raw, Clock clock) {
    try {
      Instant when =
          ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      Duration delta = Duration.between(clock.instant(), when);
      return Optional.of(delta.isNegative() ? Duration.ZERO : delta);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  private static String first(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (name.equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
        return e.getValue().get(0);
      }
    }
    return null;
  }
}
