package net.codefinch.jev.test;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.SystemOneRequest;

/**
 * One call the {@link RecordingJevClient} received, in order.
 *
 * @param operation {@code systemone} or {@code models}
 * @param request the request, for {@code systemone}
 * @param options the per-call options as passed
 * @param at when it was received
 */
public record RecordedCall(
    String operation, Optional<SystemOneRequest> request, RequestOptions options, Instant at) {

  /** Validates the components. */
  public RecordedCall {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(at, "at");
  }

  /** The request, for a {@code systemone} call. */
  public SystemOneRequest systemOneRequest() {
    return request.orElseThrow(() -> new IllegalStateException(operation + " call has no request"));
  }
}
