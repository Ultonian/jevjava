package net.codefinch.jev.benchmarks.load;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cohort accounting, bounded by a fixed number of outcome categories and histogram buckets. */
public final class LoadAccounting {
  /** Result categories; exception messages and response objects are never retained. */
  public enum Outcome {
    SUCCESS,
    HTTP4XX,
    HTTP5XX,
    CANCELLED,
    DEADLINE,
    OTHER
  }

  private final Map<Outcome, LatencyHistogram> latency = new java.util.EnumMap<>(Outcome.class);
  private final Map<Outcome, Long> outcomes = new java.util.EnumMap<>(Outcome.class);
  private long admitted;
  private long completed;
  private long inWindow;
  private long successesInWindow;
  private int active;
  private int peak;

  /** All storage is allocated before admission. */
  public LoadAccounting() {
    for (Outcome outcome : Outcome.values()) {
      latency.put(outcome, new LatencyHistogram());
      outcomes.put(outcome, 0L);
    }
  }

  /** In a closed loop every offered invocation is admitted into an available caller slot. */
  public synchronized void admit() {
    admitted++;
    peak = Math.max(peak, ++active);
  }

  /** Includes every eventual result, even if it completes after the measurement window. */
  public synchronized void complete(Outcome outcome, long elapsed, boolean withinWindow) {
    if (active == 0) {
      throw new IllegalStateException("Completion without admission");
    }
    active--;
    completed++;
    outcomes.put(outcome, outcomes.get(outcome) + 1);
    latency.get(outcome).record(elapsed);
    if (withinWindow) {
      inWindow++;
      if (outcome == Outcome.SUCCESS) {
        successesInWindow++;
      }
    }
  }

  /** Immutable-by-construction report, taken after bounded drain/cleanup. */
  public synchronized Map<String, Object> snapshot(
      long windowNanos, long drainNanos, boolean forced) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("offered", admitted);
    result.put("admitted", admitted);
    result.put("completed", completed);
    result.put("completedInWindow", inWindow);
    result.put("drainCompletions", completed - inWindow);
    result.put("unfinished", admitted - completed);
    result.put("peakInFlight", peak);
    result.put("windowNanos", windowNanos);
    result.put("drainNanos", drainNanos);
    result.put("forcedCleanup", forced);
    result.put("terminalPerSecond", inWindow * 1e9 / windowNanos);
    result.put("successfulPerSecond", successesInWindow * 1e9 / windowNanos);
    result.put("outcomes", new java.util.EnumMap<>(outcomes));
    Map<String, Object> histograms = new LinkedHashMap<>();
    latency.forEach((key, value) -> histograms.put(key.name(), value.snapshot()));
    result.put("latencyByOutcome", histograms);
    return result;
  }
}
