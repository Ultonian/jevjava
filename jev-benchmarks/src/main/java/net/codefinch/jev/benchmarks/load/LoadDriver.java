package net.codefinch.jev.benchmarks.load;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-concurrency closed-loop driver. Only one invocation per virtual caller can be pending. */
public final class LoadDriver {
  private LoadDriver() {}

  /**
   * An invocation returns its outcome; implementations own cancellation of their pending future.
   */
  @FunctionalInterface
  public interface Invocation {
    /** Performs one terminal invocation, cancelling pending work if interrupted. */
    LoadAccounting.Outcome call() throws InterruptedException;
  }

  /** Runs one isolated cohort; warm-up is a separate call, drained before measurement begins. */
  public static Map<String, Object> run(
      int concurrency, Duration window, Duration drain, Invocation invocation)
      throws InterruptedException {
    if (concurrency < 1
        || concurrency > 128
        || window.isNegative()
        || window.isZero()
        || window.compareTo(Duration.ofMinutes(1)) > 0
        || drain.isNegative()
        || drain.isZero()
        || drain.compareTo(Duration.ofMinutes(1)) > 0) {
      throw new IllegalArgumentException("Invalid bounded driver configuration");
    }
    final LoadAccounting accounting = new LoadAccounting();
    final CountDownLatch ready = new CountDownLatch(concurrency);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicLong stopAt = new AtomicLong();
    var callers = Executors.newVirtualThreadPerTaskExecutor();
    var futures = new ArrayList<Future<?>>();
    try {
      for (int i = 0; i < concurrency; i++) {
        futures.add(
            callers.submit(
                () -> {
                  ready.countDown();
                  try {
                    start.await();
                    while (!Thread.currentThread().isInterrupted()) {
                      long before = System.nanoTime();
                      if (before - stopAt.get() >= 0) {
                        break;
                      }
                      accounting.admit();
                      LoadAccounting.Outcome outcome;
                      try {
                        outcome = invocation.call();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        outcome = LoadAccounting.Outcome.CANCELLED;
                      } catch (RuntimeException e) {
                        outcome = LoadAccounting.Outcome.OTHER;
                      }
                      long after = System.nanoTime();
                      accounting.complete(outcome, after - before, after - stopAt.get() < 0);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }));
      }
      if (!ready.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Caller startup watchdog expired");
      }
      final Instant windowStart = Instant.now();
      stopAt.set(System.nanoTime() + window.toNanos());
      start.countDown();
      callers.shutdown();
      boolean forced =
          !callers.awaitTermination(window.plus(drain).toNanos(), TimeUnit.NANOSECONDS);
      if (forced) {
        callers.shutdownNow();
        callers.awaitTermination(2, TimeUnit.SECONDS);
      }
      for (Future<?> future : futures) {
        if (future.isDone() && !future.isCancelled()) {
          try {
            future.get();
          } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Caller task failed", e.getCause());
          }
        }
      }
      Map<String, Object> result =
          new LinkedHashMap<>(
              accounting.snapshot(
                  window.toNanos(), Math.max(0, System.nanoTime() - stopAt.get()), forced));
      result.put("windowStart", windowStart.toString());
      result.put("windowEnd", windowStart.plus(window).toString());
      result.put(
          "windowClockMapping",
          "Instant sampled immediately before monotonic start; end derived from duration");
      return result;
    } finally {
      callers.shutdownNow();
      start.countDown();
    }
  }
}
