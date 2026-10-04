package net.codefinch.jev;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import net.codefinch.jev.model.SystemOneRequest;

/**
 * Per-call HTTP options, separate from what is being asked ({@link SystemOneRequest}).
 *
 * @param timeout per-attempt timeout override (headers and body delivery); strictly positive
 * @param deadline whole-operation deadline override (all attempts, body delivery and backoff):
 *     empty inherits the client setting, {@link Duration#ZERO} disables the deadline for this call,
 *     a positive value sets it
 * @param headers extra request headers, merged case-insensitively over the client defaults; the
 *     SDK-controlled headers ({@code Authorization}, {@code Accept}, {@code Content-Type}, {@code
 *     User-Agent}, {@code X-TypeSafe-SDK}, {@code X-TypeSafe-Runtime}, and {@code
 *     X-TypeSafe-Retry-Count}) always win; other headers, including other {@code X-TypeSafe-*}
 *     names, remain caller-controlled
 * @param retry a partial override of the client's retry policy for this call, applied to the
 *     client's policy (e.g. {@code p -> p.withMaxRetries(0)}); replace it wholesale with {@code p
 *     -> other}
 * @since 0.1.0
 */
public record RequestOptions(
    Optional<Duration> timeout,
    Optional<Duration> deadline,
    Map<String, String> headers,
    Optional<UnaryOperator<RetryPolicy>> retry) {

  /** No overrides. */
  public static final RequestOptions NONE =
      new RequestOptions(Optional.empty(), Optional.empty(), Map.of(), Optional.empty());

  /** Validates and snapshots the components. */
  public RequestOptions {
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(retry, "retry");
    timeout.ifPresent(t -> requirePositive(t, "timeout"));
    deadline.ifPresent(d -> requireNonNegative(d, "deadline"));
    Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    copy.putAll(headers);
    headers = Collections.unmodifiableMap(copy);
  }

  /** Starts building options. */
  public static Builder builder() {
    return new Builder();
  }

  private static void requireNonNegative(Duration d, String name) {
    if (d.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  private static void requirePositive(Duration d, String name) {
    if (d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  /**
   * Builds {@link RequestOptions}.
   *
   * @since 0.1.0
   */
  public static final class Builder {
    private Duration timeout;
    private Duration deadline;
    private UnaryOperator<RetryPolicy> retry;
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private Builder() {}

    /** Per-attempt timeout. */
    public Builder timeout(Duration timeout) {
      this.timeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    /** Whole-operation deadline for this call; {@link Duration#ZERO} disables it. */
    public Builder deadline(Duration deadline) {
      this.deadline = Objects.requireNonNull(deadline, "deadline");
      return this;
    }

    /** Disables the operation deadline for this call (same as {@code deadline(Duration.ZERO)}). */
    public Builder noDeadline() {
      return deadline(Duration.ZERO);
    }

    /** Partially overrides the client's retry policy for this call. */
    public Builder retry(UnaryOperator<RetryPolicy> retry) {
      this.retry = Objects.requireNonNull(retry, "retry");
      return this;
    }

    /** Adds a request header. */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /** Finishes the options. */
    public RequestOptions build() {
      return new RequestOptions(
          Optional.ofNullable(timeout),
          Optional.ofNullable(deadline),
          headers,
          Optional.ofNullable(retry));
    }
  }
}
