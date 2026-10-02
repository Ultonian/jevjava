package net.codefinch.jev.benchmarks.diagnostics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded post-recording stack counts; native samples and Java execution stay separate. */
final class SampleSummary {
  private record Stack(String event, String threadKind, List<String> frames) {}

  private final int capacity;
  private final Map<Stack, Long> stacks = new LinkedHashMap<>();
  private final Map<String, Long> roles = new TreeMap<>();
  private final Map<String, Long> threads = new TreeMap<>();
  private final Map<String, Long> truncated = new TreeMap<>();
  private final Map<String, Long> missing = new TreeMap<>();
  private long unretained;

  SampleSummary(int capacity) {
    this.capacity = capacity;
  }

  void add(String event, List<String> frames, String threadName, String threadKind, boolean cut) {
    roles.merge(
        event + "/" + RestrictedRecording.allocationRole(frames, threadName), 1L, Long::sum);
    threads.merge(event + "/" + threadKind, 1L, Long::sum);
    if (cut) {
      truncated.merge(event, 1L, Long::sum);
    }
    if (frames.isEmpty()) {
      missing.merge(event, 1L, Long::sum);
    }
    Stack key = new Stack(event, threadKind, List.copyOf(frames));
    if (stacks.containsKey(key) || stacks.size() < capacity) {
      stacks.merge(key, 1L, Long::sum);
    } else {
      unretained++;
    }
  }

  Map<String, Object> snapshot() {
    return Map.of(
        "countsByRole", roles,
        "countsByThreadKind", threads,
        "truncatedStacks", truncated,
        "missingStacks", missing,
        "stackCapacity", capacity,
        "unretainedStackSamples", unretained,
        "retainedStacks",
            stacks.entrySet().stream()
                .sorted(Map.Entry.<Stack, Long>comparingByValue().reversed())
                .map(
                    entry ->
                        Map.of(
                            "event", entry.getKey().event(),
                            "threadKind", entry.getKey().threadKind(),
                            "frames", entry.getKey().frames(),
                            "count", entry.getValue()))
                .toList(),
        "interpretation",
            "sample counts, not CPU-time or latency shares; Java/native separate; first distinct"
                + " stack shapes retained to capacity, counted thereafter; unretained explicit");
  }
}
