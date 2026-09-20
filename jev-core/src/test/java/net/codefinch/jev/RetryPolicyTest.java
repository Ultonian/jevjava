package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  private static final Map<String, List<String>> NO_HEADERS = Map.of();

  /** A generator whose nextDouble() returns a fixed value in [0, 1). */
  private static RandomGenerator fixed(double value) {
    long bits = (long) (value * (1L << 53));
    return new RandomGenerator() {
      @Override
      public long nextLong() {
        return bits << 11; // nextDouble() uses the top 53 bits
      }
    };
  }

  @Test
  void defaultsMatchBothUpstreamSdks() {
    RetryPolicy p = RetryPolicy.DEFAULT;
    assertThat(p.maxRetries()).isEqualTo(2);
    assertThat(p.backoffInitial()).isEqualTo(Duration.ofMillis(500));
    assertThat(p.backoffMax()).isEqualTo(Duration.ofSeconds(5));
    assertThat(p.backoffJitter()).isEqualTo(0.25);
    assertThat(p.httpStatuses())
        .contains(408, 429, 500, 529, 599)
        .doesNotContain(400, 404, 422, 600);
    assertThat(p.httpStatuses()).hasSize(102);
    assertThat(p.respectRetryAfter()).isTrue();
    assertThat(p.maxRetryAfter()).isEqualTo(Duration.ofSeconds(60));
    assertThat(p.retryConnectionErrors()).isTrue();
    assertThat(p.retryTimeouts()).isTrue();
    assertThat(RetryPolicy.NONE.maxRetries()).isZero();
  }

  @Test
  void backoffDoublesFrom500msCapsAt5sAndSubtractsUpTo25Percent() {
    RetryPolicy p = RetryPolicy.DEFAULT;
    assertThat(p.delay(0, Optional.empty(), fixed(0))).isEqualTo(Duration.ofMillis(500));
    assertThat(p.delay(1, Optional.empty(), fixed(0))).isEqualTo(Duration.ofMillis(1000));
    assertThat(p.delay(2, Optional.empty(), fixed(0))).isEqualTo(Duration.ofMillis(2000));
    assertThat(p.delay(3, Optional.empty(), fixed(0))).isEqualTo(Duration.ofMillis(4000));
    assertThat(p.delay(4, Optional.empty(), fixed(0))).isEqualTo(Duration.ofMillis(5000));
    assertThat(p.delay(40, Optional.empty(), fixed(0))).isEqualTo(Duration.ofMillis(5000));
    // random() = 1.0 would subtract the full 25 %; 0.999… gets within rounding of 375 ms.
    assertThat(p.delay(0, Optional.empty(), fixed(0.9999999)).toMillis()).isBetween(375L, 376L);
    assertThat(p.delay(0, Optional.empty(), fixed(0.5)))
        .isEqualTo(Duration.ofMillis(438)); // 500 * 0.875
    assertThat(p.withBackoffJitter(0).delay(0, Optional.empty(), fixed(0.99)))
        .isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void serverDelayWinsWhenHonouredAndWithinCap() {
    RetryPolicy p = RetryPolicy.DEFAULT;
    assertThat(p.delay(0, Optional.of(Duration.ofMillis(1234)), fixed(0)))
        .isEqualTo(Duration.ofMillis(1234));
    assertThat(p.delay(0, Optional.of(Duration.ofSeconds(60)), fixed(0)))
        .isEqualTo(Duration.ofSeconds(60));
    assertThat(p.delay(0, Optional.of(Duration.ofSeconds(61)), fixed(0)))
        .isEqualTo(Duration.ofMillis(500));
    assertThat(
            p.withRetryAfter(false, Duration.ofSeconds(60))
                .delay(0, Optional.of(Duration.ofMillis(1)), fixed(0)))
        .isEqualTo(Duration.ofMillis(500));
    assertThat(p.delay(0, Optional.of(Duration.ZERO), fixed(0))).isEqualTo(Duration.ZERO);
  }

  @Test
  void zeroBackoffDisablesWaiting() {
    assertThat(
            RetryPolicy.DEFAULT
                .withBackoff(Duration.ZERO, Duration.ofSeconds(5))
                .delay(0, Optional.empty(), fixed(0)))
        .isEqualTo(Duration.ZERO);
    assertThat(
            RetryPolicy.DEFAULT
                .withBackoff(Duration.ofMillis(500), Duration.ZERO)
                .delay(3, Optional.empty(), fixed(0)))
        .isEqualTo(Duration.ZERO);
  }

  @Test
  void retryabilityRules() {
    RetryPolicy p = RetryPolicy.DEFAULT;
    assertThat(p.isRetryable(JevApiException.fromStatus(429, NO_HEADERS, "", null))).isTrue();
    assertThat(p.isRetryable(JevApiException.fromStatus(408, NO_HEADERS, "", null))).isTrue();
    assertThat(p.isRetryable(JevApiException.fromStatus(529, NO_HEADERS, "", null))).isTrue();
    assertThat(p.isRetryable(JevApiException.fromStatus(400, NO_HEADERS, "", null))).isFalse();
    assertThat(p.isRetryable(new JevResponseValidationException(200, NO_HEADERS, "", null, "x")))
        .isFalse();
    assertThat(p.isRetryable(new JevConnectionException("c", null))).isTrue();
    assertThat(p.isRetryable(new JevTimeoutException("t", null))).isTrue();
    assertThat(p.withFailures(false, true).isRetryable(new JevConnectionException("c", null)))
        .isFalse();
    assertThat(p.withFailures(false, true).isRetryable(new JevTimeoutException("t", null)))
        .isTrue();
    assertThat(p.withFailures(true, false).isRetryable(new JevTimeoutException("t", null)))
        .isFalse();
    assertThat(
            p.withHttpStatuses(Set.of(418))
                .isRetryable(JevApiException.fromStatus(429, NO_HEADERS, "", null)))
        .isFalse();
    assertThat(
            p.withHttpStatuses(Set.of(418))
                .isRetryable(JevApiException.fromStatus(418, NO_HEADERS, "", null)))
        .isTrue();
    assertThat(p.isRetryable(new JevException("plain"))).isFalse();
  }

  @Test
  void predicateExtendsButTerminalFailuresNeverRetry() {
    RetryPolicy permissive = RetryPolicy.DEFAULT.withPredicate(e -> true);
    assertThat(permissive.isRetryable(JevApiException.fromStatus(400, NO_HEADERS, "", null)))
        .isTrue();
    assertThat(
            permissive.isRetryable(
                new JevResponseValidationException(200, NO_HEADERS, "", null, "x")))
        .isTrue();
    assertThat(permissive.isRetryable(new JevDeadlineExceededException("d", null))).isFalse();
    assertThat(permissive.isRetryable(new JevInterruptedException("i", null))).isFalse();
  }

  @Test
  void validation() {
    assertThatThrownBy(() -> RetryPolicy.DEFAULT.withMaxRetries(-1))
        .hasMessageContaining("maxRetries");
    assertThatThrownBy(() -> RetryPolicy.DEFAULT.withBackoffJitter(1.5))
        .hasMessageContaining("backoffJitter");
    assertThatThrownBy(() -> RetryPolicy.DEFAULT.withBackoffJitter(Double.NaN))
        .hasMessageContaining("backoffJitter");
    assertThatThrownBy(() -> RetryPolicy.DEFAULT.withBackoff(Duration.ofSeconds(-1), Duration.ZERO))
        .hasMessageContaining("backoffInitial");
    assertThatThrownBy(() -> RetryPolicy.DEFAULT.withRetryAfter(true, Duration.ofSeconds(-1)))
        .hasMessageContaining("maxRetryAfter");
    assertThatThrownBy(() -> RetryPolicy.DEFAULT.httpStatuses().add(1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requestOptionsApplyPartialOverrides() {
    RetryPolicy client = RetryPolicy.DEFAULT.withMaxRetries(5);
    RequestOptions partial = RequestOptions.builder().retry(p -> p.withBackoffJitter(0)).build();
    RetryPolicy resolved = partial.resolveRetry(client);
    assertThat(resolved.maxRetries()).isEqualTo(5);
    assertThat(resolved.backoffJitter()).isZero();
    assertThat(RequestOptions.NONE.resolveRetry(client)).isSameAs(client);
    assertThat(RequestOptions.builder().retry(p -> RetryPolicy.NONE).build().resolveRetry(client))
        .isSameAs(RetryPolicy.NONE);
    assertThatThrownBy(() -> RequestOptions.builder().retry(p -> null).build().resolveRetry(client))
        .isInstanceOf(NullPointerException.class);
  }
}
