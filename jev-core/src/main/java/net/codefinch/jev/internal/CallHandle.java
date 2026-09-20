package net.codefinch.jev.internal;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Everything a running call owns that cancellation must reach: the cancelled flag, a latch that
 * wakes backoff sleeps, and the in-flight HTTP exchange. One per call; shared between the caller's
 * future and the worker.
 */
public final class CallHandle {
  private final CountDownLatch cancelLatch = new CountDownLatch(1);
  private volatile boolean cancelled;
  private volatile Future<?> inFlight;

  /** Whether {@link #cancel()} has been called. */
  public boolean isCancelled() {
    return cancelled;
  }

  /** Marks the call cancelled, wakes any sleep and cancels the in-flight exchange. Idempotent. */
  public void cancel() {
    cancelled = true;
    cancelLatch.countDown();
    Future<?> f = inFlight;
    if (f != null) {
      f.cancel(true);
    }
  }

  /** Registers the current HTTP exchange; cancels it immediately if already cancelled. */
  public void inFlight(Future<?> exchange) {
    inFlight = exchange;
    if (cancelled) {
      exchange.cancel(true);
    }
  }

  /** Forgets the current exchange once it has completed. */
  public void clearInFlight() {
    inFlight = null;
  }

  /**
   * Waits up to {@code delay} for cancellation.
   *
   * @return true if cancelled before the delay elapsed
   */
  public boolean awaitCancel(Duration delay) throws InterruptedException {
    return cancelLatch.await(Math.max(0, delay.toNanos()), TimeUnit.NANOSECONDS);
  }
}
