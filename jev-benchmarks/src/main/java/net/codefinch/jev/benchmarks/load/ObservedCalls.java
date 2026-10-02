package net.codefinch.jev.benchmarks.load;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.codefinch.jev.CallObserver;

/** Observer accounting exists only in explicitly observed or diagnostic cells. */
final class ObservedCalls implements CallObserver {
  private final CallObserver delegate;
  private final ObserverCorrelation correlation;
  private final AtomicLong expected = new AtomicLong();
  private final AtomicLong finished = new AtomicLong();
  private final AtomicLong attempts = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private volatile String lastFailureClass = "none";

  ObservedCalls(CallObserver delegate, ObserverCorrelation correlation) {
    this.delegate = delegate;
    this.correlation = correlation;
  }

  void register(String id) {
    expected.incrementAndGet();
    if (correlation != null) {
      correlation.reserve(id);
    }
  }

  void result(String id, long observed) {
    if (correlation != null) {
      correlation.result(id, observed);
    }
  }

  void failed(String id) {
    if (correlation != null) {
      correlation.failed(id);
    }
  }

  @Override
  public void onAttempt(Attempt attempt) {
    attempts.incrementAndGet();
    try {
      delegate.onAttempt(attempt);
    } catch (RuntimeException e) {
      lastFailureClass = e.getClass().getName();
      failures.incrementAndGet();
    }
  }

  @Override
  public void onCall(Call call) {
    try {
      delegate.onCall(call);
      long finish = System.nanoTime();
      if (correlation != null && call.outcome() == Outcome.SUCCESS) {
        String id = call.response().orElseThrow().requestId().orElseThrow();
        correlation.observer(id, finish);
      }
    } catch (RuntimeException e) {
      lastFailureClass = e.getClass().getName();
      failures.incrementAndGet();
    } finally {
      finished.incrementAndGet();
    }
  }

  boolean await(Duration bound) throws InterruptedException {
    long end = System.nanoTime() + bound.toNanos();
    while (finished.get() < expected.get() && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    return finished.get() == expected.get();
  }

  Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("expected", expected.get());
    result.put("finished", finished.get());
    result.put("unmatchedCallbacks", expected.get() - finished.get());
    result.put("attempts", attempts.get());
    result.put("observerFailures", failures.get());
    result.put("lastFailureClass", lastFailureClass);
    if (correlation != null) {
      result.put("correlation", correlation.snapshot());
    }
    return result;
  }
}
