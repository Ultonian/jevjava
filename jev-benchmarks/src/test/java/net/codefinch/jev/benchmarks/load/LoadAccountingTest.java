package net.codefinch.jev.benchmarks.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;

class LoadAccountingTest {
  @Test
  void drainCompletionsKeepTheirLatencyButDoNotInflateWindowThroughput() {
    var accounting = new LoadAccounting();
    accounting.admit();
    accounting.admit();
    accounting.complete(LoadAccounting.Outcome.SUCCESS, 1200, true);
    accounting.complete(LoadAccounting.Outcome.HTTP5XX, 9000, false);
    var result = Json.toTree(accounting.snapshot(1_000_000_000, 100, false));
    assertThat(result.path("completed").asLong()).isEqualTo(2);
    assertThat(result.path("completedInWindow").asLong()).isEqualTo(1);
    assertThat(result.path("drainCompletions").asLong()).isEqualTo(1);
    assertThat(result.path("terminalPerSecond").asDouble()).isEqualTo(1);
    assertThat(result.path("latencyByOutcome").path("HTTP5XX").path("recorded").asLong())
        .isEqualTo(1);
    assertThatThrownBy(() -> accounting.complete(LoadAccounting.Outcome.SUCCESS, 1, true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void histogramReportsOverflowAndSuppressesMisleadingPercentiles() {
    var histogram = new LatencyHistogram();
    for (int i = 0; i < 100; i++) {
      histogram.record(1000);
    }
    assertThat(Json.toTree(histogram.snapshot()).path("p99Micros").asLong()).isEqualTo(1);
    histogram.record(Duration.ofSeconds(61).toNanos());
    var result = Json.toTree(histogram.snapshot());
    assertThat(result.path("overflow").asLong()).isEqualTo(1);
    assertThat(result.path("recorded").asLong()).isEqualTo(100);
    assertThat(result.path("p99Micros").isNull()).isTrue();
    assertThatThrownBy(() -> histogram.record(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void boundedDriverForcesInterruptedWorkToSettle() throws Exception {
    CountDownLatch admitted = new CountDownLatch(1);
    try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
      var future =
          worker.submit(
              () ->
                  LoadDriver.run(
                      1,
                      Duration.ofMillis(500),
                      Duration.ofMillis(20),
                      () -> {
                        admitted.countDown();
                        new CountDownLatch(1).await();
                        return LoadAccounting.Outcome.SUCCESS;
                      }));
      assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
      var result = Json.toTree(future.get(5, TimeUnit.SECONDS));
      assertThat(result.path("forcedCleanup").asBoolean()).isTrue();
      assertThat(result.path("unfinished").asLong()).isZero();
      assertThat(result.path("outcomes").path("CANCELLED").asLong()).isEqualTo(1);
    }
  }

  @Test
  void correlationHandlesBothOrdersNegativeOffsetsOverflowAndDrain() throws Exception {
    var correlation = new ObserverCorrelation(1);
    correlation.reserve("1");
    correlation.observer("1", 100);
    assertThat(correlation.awaitDrain(Duration.ZERO)).isFalse();
    correlation.result("1", 200);
    assertThat(correlation.awaitDrain(Duration.ZERO)).isTrue();
    correlation.reserve("2");
    correlation.result("2", 100);
    correlation.observer("2", 300);
    var complete = Json.toTree(correlation.snapshot());
    assertThat(complete.path("paired").asLong()).isEqualTo(2);
    assertThat(complete.path("negativeMagnitude").path("recorded").asLong()).isEqualTo(1);
    assertThat(complete.path("positiveMagnitude").path("recorded").asLong()).isEqualTo(1);
    correlation.reserve("3");
    correlation.reserve("4");
    correlation.result("4", 400);
    assertThat(Json.toTree(correlation.snapshot()).path("overflow").asLong()).isEqualTo(1);
    assertThat(Json.toTree(correlation.snapshot()).path("unmatched").asLong()).isEqualTo(1);
    correlation.failed("3");
    assertThat(correlation.awaitDrain(Duration.ZERO)).isTrue();
    assertThat(Json.toTree(correlation.snapshot()).path("complete").asBoolean()).isFalse();
  }
}
