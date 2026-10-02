package net.codefinch.jev.benchmarks.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.HdrHistogram.Histogram;

/** Fixed-range microsecond histogram; overflow is counted, never silently clamped or resized. */
public final class LatencyHistogram {
  private static final long MAX_MICROS = 60_000_000;
  private final Histogram values = new Histogram(1, MAX_MICROS, 3);
  private long overflow;
  private long maximumNanos;

  /** Records a nonnegative duration, rounding up to microseconds. */
  public synchronized void record(long nanos) {
    if (nanos < 0) {
      throw new IllegalArgumentException("Negative latency");
    }
    maximumNanos = Math.max(maximumNanos, nanos);
    long micros = nanos / 1000 + (nanos % 1000 == 0 ? 0 : 1);
    if (micros > MAX_MICROS) {
      overflow++;
    } else {
      values.recordValue(micros);
    }
  }

  /** Percentiles include sample counts; raw bounded buckets permit later distribution review. */
  public synchronized Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    long count = values.getTotalCount();
    result.put("recorded", count);
    result.put("overflow", overflow);
    result.put("unit", "microseconds (rounded up)");
    result.put("highestTrackableMicros", MAX_MICROS);
    result.put("significantDigits", 3);
    result.put("maxNanos", maximumNanos);
    result.put("p50Micros", count >= 2 && overflow == 0 ? values.getValueAtPercentile(50) : null);
    result.put("p95Micros", count >= 20 && overflow == 0 ? values.getValueAtPercentile(95) : null);
    result.put("p99Micros", count >= 100 && overflow == 0 ? values.getValueAtPercentile(99) : null);
    var buckets = new ArrayList<Map<String, Long>>();
    for (var bucket : values.recordedValues()) {
      buckets.add(
          Map.of(
              "throughMicros",
              bucket.getValueIteratedTo(),
              "count",
              bucket.getCountAtValueIteratedTo()));
    }
    result.put("buckets", buckets);
    return result;
  }
}
