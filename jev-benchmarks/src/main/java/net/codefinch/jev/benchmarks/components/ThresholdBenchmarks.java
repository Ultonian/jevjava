package net.codefinch.jev.benchmarks.components;

import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.config.JvmSettings;
import net.codefinch.jev.patterns.ConfidenceGate;
import net.codefinch.jev.patterns.NoulThreshold;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Component measurements; run explicitly with JMH, never as unit tests. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
    value = 3,
    jvmArgs = {JvmSettings.MIN_HEAP, JvmSettings.MAX_HEAP, JvmSettings.GC})
@State(Scope.Thread)
public class ThresholdBenchmarks {
  /** Values below and at the two decision boundaries. */
  @Param({"0.1", "0.2", "0.8"})
  public double value;

  private ConfidenceGate gate = ConfidenceGate.of(0.2, 0.8);
  private NoulThreshold threshold = NoulThreshold.of(0.2, 0.8);

  /** Prepare caller-selected thresholds. */
  @Setup(Level.Trial)
  public void prepare() {
    gate = ConfidenceGate.of(0.2, 0.8);
    threshold = NoulThreshold.of(0.2, 0.8);
  }

  /** Routes a confidence value. */
  @Benchmark
  public ConfidenceGate.Decision confidence() {
    return gate.decide(value);
  }

  /** Routes a yes/no probability. */
  @Benchmark
  public NoulThreshold.Decision noul() {
    return threshold.decide(value);
  }
}
