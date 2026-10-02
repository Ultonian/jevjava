package net.codefinch.jev.benchmarks.load;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded successful-systemOne correlation, reserving IDs before either event can arrive. */
public final class ObserverCorrelation {
  private final int capacity;
  private final Map<String, Pair> pending = new HashMap<>();
  private final LatencyHistogram positive = new LatencyHistogram();
  private final LatencyHistogram negative = new LatencyHistogram();
  private long zero;
  private long overflow;
  private long missing;
  private long paired;
  private long failed;
  private int peakPending;

  /** Capacity is independent of run duration. */
  public ObserverCorrelation(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Positive capacity required");
    }
    this.capacity = capacity;
  }

  /** Reserves before invoking the SDK, so a late callback cannot recreate a released pair. */
  public synchronized void reserve(String id) {
    if (pending.containsKey(id)) {
      throw new IllegalStateException("Duplicate correlation ID");
    }
    if (pending.size() == capacity) {
      overflow++;
    } else {
      pending.put(id, new Pair());
      peakPending = Math.max(peakPending, pending.size());
    }
  }

  /** Records when the caller observed the returned successful result. */
  public synchronized void result(String id, long nanos) {
    arrive(id, nanos, false);
  }

  /** Records after the configured observer work has finished. */
  public synchronized void observer(String id, long nanos) {
    arrive(id, nanos, true);
  }

  private void arrive(String id, long nanos, boolean observer) {
    Pair pair = pending.get(id);
    if (pair == null) {
      missing++;
      return;
    }
    if (observer) {
      if (pair.observer != null) {
        throw new IllegalStateException("Duplicate observer event");
      }
      pair.observer = nanos;
    } else {
      if (pair.result != null) {
        throw new IllegalStateException("Duplicate result event");
      }
      pair.result = nanos;
    }
    if (pair.observer != null && pair.result != null) {
      long offset = pair.observer - pair.result;
      if (offset > 0) {
        positive.record(offset);
      } else if (offset < 0) {
        negative.record(-offset);
      } else {
        zero++;
      }
      paired++;
      pending.remove(id);
      notifyAll();
    }
  }

  /** Errors are outside this metric and release their reservation. */
  public synchronized void failed(String id) {
    pending.remove(id);
    failed++;
    notifyAll();
  }

  /** Result completion alone is not observer completion. */
  public synchronized boolean awaitDrain(Duration limit) throws InterruptedException {
    long end = System.nanoTime() + limit.toNanos();
    while (!pending.isEmpty()) {
      long remaining = end - System.nanoTime();
      if (remaining <= 0) {
        return false;
      }
      java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
    }
    return true;
  }

  /** Snapshot preserves unmatched/overflow evidence without retaining response objects. */
  public synchronized Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "definition", "observer work finish minus caller result observation; signed nanoseconds");
    result.put("capacity", capacity);
    result.put("peakPending", peakPending);
    result.put("paired", paired);
    result.put("unmatched", pending.size());
    result.put("overflow", overflow);
    result.put("unknownEvents", missing);
    result.put("excludedFailures", failed);
    result.put("zero", zero);
    result.put("positiveMagnitude", positive.snapshot());
    result.put("negativeMagnitude", negative.snapshot());
    result.put("complete", pending.isEmpty() && overflow == 0 && missing == 0);
    return result;
  }

  private static final class Pair {
    private Long result;
    private Long observer;
  }
}
