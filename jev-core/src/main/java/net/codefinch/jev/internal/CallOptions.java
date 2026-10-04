package net.codefinch.jev.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.RetryPolicy;

/** Resolves per-call overrides against the client's defaults before execution. */
final class CallOptions {
  private CallOptions() {}

  static RetryPolicy resolveRetry(RequestOptions options, RetryPolicy clientPolicy) {
    return options
        .retry()
        .map(r -> Objects.requireNonNull(r.apply(clientPolicy), "retry override"))
        .orElse(clientPolicy);
  }

  static Optional<Duration> resolveDeadline(
      RequestOptions options, Optional<Duration> clientDeadline) {
    if (options.deadline().map(Duration::isZero).orElse(false)) {
      return Optional.empty();
    }
    return options.deadline().or(() -> clientDeadline);
  }
}
