package net.codefinch.jev.benchmarks.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LoadSessionTest {
  @Test
  void executorPilotCellsDrainBothCohortsAtConcurrencyEight() throws Exception {
    for (var cell : LoadCase.selection("async-executor")) {
      try (var session = new LoadSession(cell)) {
        for (int i = 0; i < 2; i++) {
          var result = Json.toTree(session.cohort(Duration.ofMillis(100)));
          LoadTrial.validate(result, cell);
          assertThat(result.path("peakInFlight").asInt()).isBetween(1, 8);
          assertThat(result.path("serverAfter").path("activeHandlers").asInt()).isZero();
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(LoadCase.Submission.class)
  void bothCallStylesAccountForEveryInvocationAndKeepCohortsSeparate(LoadCase.Submission style)
      throws Exception {
    var cell = new LoadCase(style, 1, LoadCase.Variant.BASE, false);
    try (var session = new LoadSession(cell)) {
      for (int i = 0; i < 2; i++) {
        var result = Json.toTree(session.cohort(Duration.ofMillis(100)));
        LoadTrial.validate(result, cell);
        ObjectNode corrupted = result.deepCopy();
        corrupted.put("drainCompletions", result.path("drainCompletions").asLong() + 1);
        assertThatThrownBy(() -> LoadTrial.validate(corrupted, cell))
            .isInstanceOf(IllegalStateException.class);
        assertThat(result.has("observation")).isFalse();
        assertThat(result.path("peakInFlight").asInt()).isEqualTo(1);
        assertThat(result.path("serverAfter").path("protocol").asText()).isEqualTo("HTTP/1.1");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = LoadCase.Variant.class,
      names = {"ERROR400", "ERROR503", "MODELS", "OFFSET_MICROMETER", "PLATFORM"})
  void responseKindsAndInstrumentedPathsHaveCorrectAccounting(LoadCase.Variant variant)
      throws Exception {
    var cell = new LoadCase(LoadCase.Submission.ASYNC, 1, variant, false);
    try (var session = new LoadSession(cell)) {
      var result = Json.toTree(session.cohort(Duration.ofMillis(100)));
      LoadTrial.validate(result, cell);
      if (cell.offsets()) {
        assertThat(result.path("observation").path("correlation").path("paired").asLong())
            .isEqualTo(result.path("admitted").asLong());
        assertThat(result.path("meters").asInt()).isPositive();
      }
    }
  }

  @Test
  void observerDrainWaitsForCallbackWorkRatherThanCallerCompletion() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var observed =
        new ObservedCalls(
            new CallObserver() {
              @Override
              public void onCall(Call call) {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            null);
    observed.register("1");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var callback =
          executor.submit(
              () ->
                  observed.onCall(
                      new CallObserver.Call(
                          "systemone",
                          CallObserver.Outcome.SUCCESS,
                          1,
                          Duration.ZERO,
                          OptionalInt.of(200),
                          Optional.empty(),
                          Optional.empty(),
                          Optional.empty())));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        observed.result("1", System.nanoTime());
        assertThat(observed.await(Duration.ZERO)).isFalse();
      } finally {
        release.countDown();
      }
      callback.get(5, TimeUnit.SECONDS);
      assertThat(observed.await(Duration.ZERO)).isTrue();
    }
  }
}
