package net.codefinch.jev.benchmarks.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;

class SampleSummaryTest {
  @Test
  void stackCapDoesNotDiscardCountsAndJavaNativeSamplesStaySeparate() {
    var summary = new SampleSummary(2);
    var sdk = List.of("net.codefinch.jev.internal.CallExecution.lambda$publishResult$7");
    summary.add("jdk.ExecutionSample", sdk, "", "virtual", false);
    summary.add("jdk.NativeMethodSample", sdk, "", "virtual", true);
    summary.add("jdk.ExecutionSample", List.of(), "benchmark-server-1", "platform", false);
    summary.add("jdk.ExecutionSample", sdk, "", "virtual", false);
    var result = Json.toTree(summary.snapshot());
    assertThat(result.path("retainedStacks")).hasSize(2);
    assertThat(result.path("unretainedStackSamples").asLong()).isEqualTo(1);
    assertThat(result.path("countsByRole").path("jdk.ExecutionSample/result-delivery").asLong())
        .isEqualTo(2);
    assertThat(result.path("countsByRole").path("jdk.NativeMethodSample/result-delivery").asLong())
        .isEqualTo(1);
    assertThat(result.path("countsByRole").path("jdk.ExecutionSample/server").asLong())
        .isEqualTo(1);
    assertThat(result.path("missingStacks").path("jdk.ExecutionSample").asLong()).isEqualTo(1);
    assertThat(result.path("truncatedStacks").path("jdk.NativeMethodSample").asLong()).isEqualTo(1);
    assertThat(result.path("countsByThreadKind").path("jdk.ExecutionSample/virtual").asLong())
        .isEqualTo(2);
  }
}
