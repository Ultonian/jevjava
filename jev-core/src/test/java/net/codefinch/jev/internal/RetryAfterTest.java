package net.codefinch.jev.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetryAfterTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

  private static Map<String, List<String>> headers(String... kv) {
    Map<String, List<String>> m = new java.util.HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], List.of(kv[i + 1]));
    }
    return m;
  }

  @Test
  void retryAfterMsIsPreferredOverRetryAfter() {
    assertThat(RetryAfter.parse(headers("retry-after-ms", "250", "retry-after", "5"), CLOCK))
        .contains(Duration.ofMillis(250));
    assertThat(RetryAfter.parse(headers("Retry-After", "2"), CLOCK))
        .contains(Duration.ofSeconds(2));
    assertThat(RetryAfter.parse(headers("retry-after", "1.5"), CLOCK))
        .contains(Duration.ofMillis(1500));
  }

  @Test
  void invalidMsFallsBackToRetryAfter() {
    assertThat(RetryAfter.parse(headers("retry-after-ms", "nope", "retry-after", "3"), CLOCK))
        .contains(Duration.ofSeconds(3));
    assertThat(RetryAfter.parse(headers("retry-after-ms", "-1", "retry-after", "3"), CLOCK))
        .contains(Duration.ofSeconds(3));
  }

  @Test
  void negativeNonFiniteAndGarbageAreIgnored() {
    assertThat(RetryAfter.parse(headers("retry-after", "-5"), CLOCK)).isEmpty();
    assertThat(RetryAfter.parse(headers("retry-after", "Infinity"), CLOCK)).isEmpty();
    assertThat(RetryAfter.parse(headers("retry-after", "NaN"), CLOCK)).isEmpty();
    assertThat(RetryAfter.parse(headers("retry-after", "soon"), CLOCK)).isEmpty();
    assertThat(RetryAfter.parse(headers("retry-after-ms", "1e400"), CLOCK)).isEmpty();
    assertThat(RetryAfter.parse(Map.of(), CLOCK)).isEmpty();
    assertThat(RetryAfter.parse(headers("retry-after", ""), CLOCK)).contains(Duration.ZERO);
  }

  @Test
  void httpDatesAreRelativeToNowAndNeverNegative() {
    assertThat(RetryAfter.parse(headers("retry-after", "Sun, 20 Sep 2026 12:00:30 GMT"), CLOCK))
        .contains(Duration.ofSeconds(30));
    assertThat(RetryAfter.parse(headers("retry-after", "Sun, 20 Sep 2026 11:00:00 GMT"), CLOCK))
        .contains(Duration.ZERO);
    assertThat(RetryAfter.parse(headers("retry-after", "0"))).contains(Duration.ZERO);
  }
}
