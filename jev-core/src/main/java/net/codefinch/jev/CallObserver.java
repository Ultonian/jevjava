package net.codefinch.jev;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Receives one event per HTTP attempt and one per call, for metrics and tracing. Register with
 * {@link JevClientBuilder#observer(CallObserver)}.
 *
 * <p>Events are built when they happen but delivered asynchronously on SDK virtual threads, in
 * order per call (its attempts, then its call event), never on the deadline timer, the closing
 * thread or the operation thread. A slow or blocked observer therefore delays only its own event
 * queue; an observer that throws is logged and ignored. Observation never affects the call, its
 * result, its deadline or {@code close()}.
 */
public interface CallObserver {

  /** How a call ended. */
  enum Outcome {
    /** A 2xx response that parsed. */
    SUCCESS,
    /** Any {@link JevException} the caller sees (API error, transport failure, invalid body). */
    ERROR,
    /** Cancelled by the caller, by {@code close()}, or interrupted. */
    CANCELLED,
    /** The operation deadline expired. */
    DEADLINE
  }

  /**
   * One HTTP attempt.
   *
   * @param operation {@code systemone} or {@code models}
   * @param attempt 1-based attempt number
   * @param elapsed from sending the request to the end of body delivery, or to the failure
   * @param status the HTTP status, or empty when no response was received
   * @param failure the failure this attempt produced, if any (a 5xx still has a status)
   */
  record Attempt(
      String operation,
      int attempt,
      Duration elapsed,
      OptionalInt status,
      Optional<Throwable> failure) {}

  /**
   * One call, after its last attempt (or its cancellation/expiry).
   *
   * @param operation {@code systemone} or {@code models}
   * @param outcome how it ended
   * @param attempts attempts made (0 if it never started)
   * @param elapsed from submission (executor queueing included) to the terminal state
   * @param status the last HTTP status seen, if any
   * @param model the versioned model that answered, for a successful {@code systemone}
   * @param response the response, for a successful {@code systemone}
   * @param failure the failure, for {@link Outcome#ERROR}, {@link Outcome#DEADLINE} and
   *     interruption
   */
  record Call(
      String operation,
      Outcome outcome,
      int attempts,
      Duration elapsed,
      OptionalInt status,
      Optional<String> model,
      Optional<SystemOneResponse> response,
      Optional<Throwable> failure) {}

  /** Called after every attempt. */
  default void onAttempt(Attempt attempt) {}

  /** Called once per call, when it reaches a terminal state. */
  default void onCall(Call call) {}
}
