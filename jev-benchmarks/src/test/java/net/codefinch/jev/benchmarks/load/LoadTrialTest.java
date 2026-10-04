package net.codefinch.jev.benchmarks.load;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoadTrialTest {
  @ParameterizedTest
  @CsvSource({
    "httpAttempts, 2, Admission/HTTP attempt count mismatch",
    "forcedCleanup, true, Forced cleanup was required",
    "unfinished, 1, Unfinished calls remain",
    "serverDrained, false, Server did not drain",
    "observersDrained, false, Observers did not drain",
    "peakInFlight, 2, Concurrency limit exceeded",
    "serverAfter/rejectedTasks, 1, Server rejected tasks"
  })
  void reportsTheBrokenAccountingInvariant(String field, String value, String explanation)
      throws Exception {
    ObjectNode result =
        (ObjectNode)
            Json.parse(
                """
                {"admitted":1,"offered":1,"completed":1,"completedInWindow":1,
                 "drainCompletions":0,"outcomes":{"SUCCESS":1},
                 "latencyByOutcome":{"SUCCESS":{"recorded":1,"overflow":0}},
                 "httpAttempts":1,"forcedCleanup":false,"unfinished":0,
                 "serverDrained":true,"observersDrained":true,"peakInFlight":1,
                 "serverAfter":{"rejectedTasks":0}}
                """);
    var cell = new LoadCase(LoadCase.Submission.SYNC, 1, LoadCase.Variant.BASE, false);
    LoadTrial.validate(result, cell);
    int slash = field.lastIndexOf('/');
    ObjectNode parent =
        slash < 0 ? result : (ObjectNode) result.at("/" + field.substring(0, slash));
    parent.set(field.substring(slash + 1), Json.parse(value));
    assertThatThrownBy(() -> LoadTrial.validate(result, cell))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(explanation)
        .hasMessageContaining("results.json");
  }
}
