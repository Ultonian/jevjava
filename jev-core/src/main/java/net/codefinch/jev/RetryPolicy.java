package net.codefinch.jev;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * When and how failed attempts are retried. The defaults reproduce both official SDKs exactly: two
 * retries; HTTP 408, 429 and every 5xx; connection errors and per-attempt timeouts; exponential
 * backoff from 500 ms doubling to a 5 s cap with 25 % subtractive jitter; server-suggested delays
 * honoured up to 60 s (the JavaScript SDK's cap; Python has none).
 *
 * <p>Cancellation, interruption and an expired operation deadline are never retried and never reach
 * {@link #predicate()}. The operation deadline itself is configured on the client, not here.
 *
 * @param maxRetries retries after the first attempt; {@code 0} disables retrying
 * @param backoffInitial first backoff delay, doubled per attempt
 * @param backoffMax cap on the exponential backoff
 * @param backoffJitter fraction of each delay randomly subtracted, in {@code [0, 1]}
 * @param httpStatuses HTTP statuses that are retried
 * @param respectRetryAfter whether {@code retry-after-ms} / {@code retry-after} are honoured
 * @param maxRetryAfter server delays above this fall back to backoff
 * @param retryConnectionErrors whether {@link JevConnectionException} (not timeouts) is retried
 * @param retryTimeouts whether {@link JevTimeoutException} is retried
 * @param predicate an extra rule consulted in addition to the built-in ones
 */
public record RetryPolicy(
    int maxRetries,
    Duration backoffInitial,
    Duration backoffMax,
    double backoffJitter,
    Set<Integer> httpStatuses,
    boolean respectRetryAfter,
    Duration maxRetryAfter,
    boolean retryConnectionErrors,
    boolean retryTimeouts,
    Predicate<JevException> predicate) {

  /** Statuses both official SDKs retry: 408, 429 and 500–599. */
  public static final Set<Integer> DEFAULT_STATUSES = defaultStatuses();

  /** The upstream defaults. */
  public static final RetryPolicy DEFAULT =
      new RetryPolicy(
          2,
          Duration.ofMillis(500),
          Duration.ofSeconds(5),
          0.25,
          DEFAULT_STATUSES,
          true,
          Duration.ofSeconds(60),
          true,
          true,
          e -> false);

  /** A policy that never retries. */
  public static final RetryPolicy NONE = DEFAULT.withMaxRetries(0);

  /** Validates and snapshots the components. */
  public RetryPolicy {
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must not be negative");
    }
    requireNonNegative(backoffInitial, "backoffInitial");
    requireNonNegative(backoffMax, "backoffMax");
    requireNonNegative(maxRetryAfter, "maxRetryAfter");
    if (!(backoffJitter >= 0 && backoffJitter <= 1)) {
      throw new IllegalArgumentException("backoffJitter must be between 0 and 1");
    }
    httpStatuses = Collections.unmodifiableSet(new HashSet<>(Objects.requireNonNull(httpStatuses)));
    Objects.requireNonNull(predicate, "predicate");
  }

  /** Copy with a different retry count. */
  public RetryPolicy withMaxRetries(int maxRetries) {
    return new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        backoffJitter,
        httpStatuses,
        respectRetryAfter,
        maxRetryAfter,
        retryConnectionErrors,
        retryTimeouts,
        predicate);
  }

  /** Copy with different backoff bounds. */
  public RetryPolicy withBackoff(Duration initial, Duration max) {
    return new RetryPolicy(
        maxRetries,
        initial,
        max,
        backoffJitter,
        httpStatuses,
        respectRetryAfter,
        maxRetryAfter,
        retryConnectionErrors,
        retryTimeouts,
        predicate);
  }

  /** Copy with a different jitter fraction. */
  public RetryPolicy withBackoffJitter(double jitter) {
    return new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        jitter,
        httpStatuses,
        respectRetryAfter,
        maxRetryAfter,
        retryConnectionErrors,
        retryTimeouts,
        predicate);
  }

  /** Copy with a different retried status set. */
  public RetryPolicy withHttpStatuses(Set<Integer> statuses) {
    return new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        backoffJitter,
        statuses,
        respectRetryAfter,
        maxRetryAfter,
        retryConnectionErrors,
        retryTimeouts,
        predicate);
  }

  /** Copy that honours or ignores server retry headers, with the given cap. */
  public RetryPolicy withRetryAfter(boolean respect, Duration max) {
    return new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        backoffJitter,
        httpStatuses,
        respect,
        max,
        retryConnectionErrors,
        retryTimeouts,
        predicate);
  }

  /** Copy with different connection-error / timeout switches. */
  public RetryPolicy withFailures(boolean connectionErrors, boolean timeouts) {
    return new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        backoffJitter,
        httpStatuses,
        respectRetryAfter,
        maxRetryAfter,
        connectionErrors,
        timeouts,
        predicate);
  }

  /** Copy with an extra retry rule. */
  public RetryPolicy withPredicate(Predicate<JevException> predicate) {
    return new RetryPolicy(
        maxRetries,
        backoffInitial,
        backoffMax,
        backoffJitter,
        httpStatuses,
        respectRetryAfter,
        maxRetryAfter,
        retryConnectionErrors,
        retryTimeouts,
        predicate);
  }

  /**
   * Whether a failure is retried under the built-in rules or the predicate. Terminal failures
   * ({@link JevDeadlineExceededException}, {@link JevInterruptedException}) are never retried and
   * the predicate is not consulted for them.
   */
  public boolean isRetryable(JevException failure) {
    if (failure instanceof JevDeadlineExceededException
        || failure instanceof JevInterruptedException) {
      return false;
    }
    boolean builtin =
        switch (failure) {
          case JevTimeoutException t -> retryTimeouts;
          case JevConnectionException c -> retryConnectionErrors;
          case JevApiException api -> httpStatuses.contains(api.status());
          default -> false;
        };
    return builtin || predicate.test(failure);
  }

  /**
   * The delay before retry number {@code attempt} (zero-based: the delay after the first failure is
   * attempt 0). A server-suggested delay is used as-is when honoured and within {@link
   * #maxRetryAfter()}; otherwise capped exponential backoff with subtractive jitter.
   */
  public Duration delay(int attempt, Optional<Duration> serverDelay, RandomGenerator random) {
    if (respectRetryAfter && serverDelay.isPresent()) {
      Duration suggested = serverDelay.get();
      if (suggested.compareTo(maxRetryAfter) <= 0) {
        return suggested;
      }
    }
    if (backoffInitial.isZero() || backoffMax.isZero()) {
      return Duration.ZERO;
    }
    double exponential =
        Math.min(backoffInitial.toMillis() * Math.pow(2, attempt), backoffMax.toMillis());
    double jittered = exponential * (1 - random.nextDouble() * backoffJitter);
    return Duration.ofMillis(Math.round(jittered));
  }

  private static Set<Integer> defaultStatuses() {
    Set<Integer> statuses = new HashSet<>();
    statuses.add(408);
    statuses.add(429);
    for (int s = 500; s < 600; s++) {
      statuses.add(s);
    }
    return Collections.unmodifiableSet(statuses);
  }

  private static void requireNonNegative(Duration d, String name) {
    Objects.requireNonNull(d, name);
    if (d.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
