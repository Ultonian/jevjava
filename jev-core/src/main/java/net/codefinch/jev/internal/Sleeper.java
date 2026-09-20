package net.codefinch.jev.internal;

import java.time.Duration;

/** Waits between attempts. Injected so tests can run backoff without real time passing. */
@FunctionalInterface
public interface Sleeper {

  /**
   * Waits for {@code delay} or until the call is cancelled, whichever is first.
   *
   * @return true if the wait completed, false if it was cut short by cancellation
   * @throws InterruptedException if the waiting thread was interrupted
   */
  boolean sleep(Duration delay, CallHandle handle) throws InterruptedException;

  /** Real waiting on the handle's cancellation latch. */
  Sleeper REAL = (delay, handle) -> !handle.awaitCancel(delay);
}
