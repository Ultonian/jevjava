package net.codefinch.jev.benchmarks.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import net.codefinch.jev.benchmarks.load.LoadCase;
import net.codefinch.jev.internal.Json;

/** Conservative harness-pressure evidence; never establishes absolute SDK capacity. */
final class Headroom {
  private Headroom() {}

  static String describe(JsonNode trials, JsonNode row, LoadCase cell, String mode) {
    if (cell.variant() == LoadCase.Variant.IMMEDIATE) {
      return "not applicable: synthetic transport; no HTTP server";
    }
    if (cell.control()) {
      return "transport control; consumes body, no SDK parsing";
    }
    if (mode.equals("pilot")) {
      return "inconclusive: executor pilot has no transport controls";
    }
    if (!mode.equals("baseline")) {
      return "inconclusive: smoke/diagnostic workload, not a capacity baseline";
    }
    LoadCase control = cell.controlCase();
    JsonNode current = find(trials, control, row.path("fork").asInt());
    int lower =
        switch (cell.concurrency()) {
          case 8 -> 1;
          case 32 -> 8;
          case 128 -> 32;
          default -> 0;
        };
    if (current == null || lower == 0) {
      return "inconclusive: missing control scaling evidence";
    }
    JsonNode single =
        find(
            trials,
            new LoadCase(control.submission(), 1, control.variant(), true),
            row.path("fork").asInt());
    JsonNode previous =
        find(
            trials,
            new LoadCase(control.submission(), lower, control.variant(), true),
            row.path("fork").asInt());
    if (single == null
        || previous == null
        || !Double.isFinite(meanQueue(single))
        || meanQueue(single) <= 0
        || !Double.isFinite(meanQueue(current))
        || !Double.isFinite(meanQueue(row))) {
      return "inconclusive: missing control queue/scaling evidence";
    }
    double ceiling = meanQueue(single) * 3;
    if (meanQueue(current) > ceiling
        || meanQueue(row) > ceiling
        || rate(current) < rate(previous)) {
      return "inconclusive: harness pressure (queue growth or control rate decline)";
    }
    return rate(current) >= rate(row) * 1.25
        ? "control suggests headroom (queue/scaling heuristic; noise unestablished)"
        : "inconclusive: server/driver ceiling not excluded";
  }

  static double meanQueue(JsonNode row) {
    JsonNode measured = row.path("result").path("measured").path("serverMeasurement");
    double tasks = measured.path("executorTasks").asDouble();
    double nanos = measured.path("executorQueueNanos").asDouble(-1);
    return tasks > 0 && nanos >= 0 ? nanos / tasks : Double.NaN;
  }

  private static double rate(JsonNode row) {
    return row.path("result").path("measured").path("terminalPerSecond").asDouble();
  }

  private static JsonNode find(JsonNode trials, LoadCase cell, int fork) {
    for (JsonNode row : trials) {
      if (row.path("cell").equals(Json.toTree(cell)) && row.path("fork").asInt() == fork) {
        return row;
      }
    }
    return null;
  }
}
