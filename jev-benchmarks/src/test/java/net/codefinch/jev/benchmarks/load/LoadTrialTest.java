package net.codefinch.jev.benchmarks.load;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;
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
    ObjectNode result = validResult();
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

  @Test
  void rejectsAnEmptyCohortEvenWhenItsTotalsAgree() throws Exception {
    ObjectNode result = validResult();
    for (String field :
        new String[] {"admitted", "offered", "completed", "completedInWindow", "httpAttempts"}) {
      result.put(field, 0);
    }
    ((ObjectNode) result.path("outcomes")).put("SUCCESS", 0);
    ((ObjectNode) result.path("latencyByOutcome").path("SUCCESS")).put("recorded", 0);
    assertAccountingFailure(result, "No requests admitted");
  }

  @Test
  void rejectsCallsThatWereAdmittedButNeverCompleted() throws Exception {
    ObjectNode result = validResult();
    result.put("admitted", 2);
    result.put("offered", 2);
    result.put("httpAttempts", 2);
    assertAccountingFailure(result, "Admitted/completed count mismatch");
  }

  @Test
  void rejectsUnexpectedOutcomesEvenWhenLatencyCountsAgree() throws Exception {
    ObjectNode result = validResult();
    ObjectNode outcomes = (ObjectNode) result.path("outcomes");
    outcomes.put("SUCCESS", 0);
    outcomes.put("HTTP5XX", 1);
    ObjectNode latency = (ObjectNode) result.path("latencyByOutcome");
    latency.set("HTTP5XX", latency.remove("SUCCESS"));
    assertAccountingFailure(result, "Unexpected outcome mix; expected SUCCESS");
  }

  private static void assertAccountingFailure(ObjectNode result, String explanation) {
    var cell = new LoadCase(LoadCase.Submission.SYNC, 1, LoadCase.Variant.BASE, false);
    assertThatThrownBy(() -> LoadTrial.validate(result, cell))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(explanation)
        .hasMessageContaining("results.json");
  }

  private static ObjectNode validResult() throws Exception {
    return (ObjectNode)
        Json.parse(
            """
            {"admitted":1,"offered":1,"completed":1,"completedInWindow":1,
             "drainCompletions":0,"outcomes":{"SUCCESS":1},
             "latencyByOutcome":{"SUCCESS":{"recorded":1,"overflow":0}},
             "httpAttempts":1,"forcedCleanup":false,"unfinished":0,
             "serverDrained":true,"observersDrained":true,"peakInFlight":1,
             "serverAfter":{"rejectedTasks":0}}
            """);
  }
}
