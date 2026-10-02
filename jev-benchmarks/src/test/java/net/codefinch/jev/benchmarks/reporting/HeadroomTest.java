package net.codefinch.jev.benchmarks.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.benchmarks.load.LoadCase;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;

class HeadroomTest {
  private static final LoadCase SDK =
      new LoadCase(LoadCase.Submission.SYNC, 32, LoadCase.Variant.BASE, false);

  @Test
  void emptyHandlersDoNotConcealQueueGrowthOrFallingControlThroughput() {
    JsonNode sdk = row(SDK, 500, 1000);
    JsonNode single = control(1, 400, 1000);
    JsonNode lower = control(8, 1000, 1000);
    for (JsonNode current : List.of(control(32, 2000, 4000), control(32, 900, 1000))) {
      assertThat(
              Headroom.describe(
                  Json.toTree(List.of(single, lower, current, sdk)), sdk, SDK, "baseline"))
          .contains("harness pressure");
    }
  }

  @Test
  void matchingControlsMustIncludeCalibrationAndSameFork() {
    JsonNode sdk = row(SDK, 500, 1000);
    JsonNode current = control(32, 2000, 1000);
    assertThat(Headroom.describe(Json.toTree(List.of(current, sdk)), sdk, SDK, "baseline"))
        .contains("missing control");
    var foreignFork = (com.fasterxml.jackson.databind.node.ObjectNode) control(1, 400, 1000);
    foreignFork.put("fork", 2);
    assertThat(
            Headroom.describe(
                Json.toTree(List.of(foreignFork, control(8, 1000, 1000), current, sdk)),
                sdk,
                SDK,
                "baseline"))
        .contains("missing control");
  }

  @Test
  void headroomNeedsQueueAndScalingEvidenceAndNeverUsesSmokeTiming() {
    JsonNode sdk = row(SDK, 500, 1000);
    JsonNode rows =
        Json.toTree(
            List.of(control(1, 400, 1000), control(8, 1000, 1000), control(32, 2000, 1000), sdk));
    assertThat(Headroom.describe(rows, sdk, SDK, "baseline")).contains("suggests headroom");
    assertThat(Headroom.describe(rows, sdk, SDK, "smoke")).contains("inconclusive");
  }

  private static JsonNode control(int concurrency, int rate, int queue) {
    return row(
        new LoadCase(LoadCase.Submission.ASYNC, concurrency, LoadCase.Variant.BASE, true),
        rate,
        queue);
  }

  private static JsonNode row(LoadCase cell, int rate, int queue) {
    return Json.toTree(
        Map.of(
            "cell",
            cell,
            "fork",
            1,
            "result",
            Map.of(
                "measured",
                Map.of(
                    "terminalPerSecond",
                    rate,
                    "serverAfter",
                    Map.of("peakActiveHandlers", 0, "workers", 256),
                    "serverMeasurement",
                    Map.of("executorTasks", 100, "executorQueueNanos", queue * 100)))));
  }
}
