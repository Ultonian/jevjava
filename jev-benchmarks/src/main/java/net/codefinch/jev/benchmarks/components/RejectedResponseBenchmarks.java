package net.codefinch.jev.benchmarks.components;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.exception.JevResponseValidationException;
import net.codefinch.jev.internal.ResponseParser;
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
    jvmArgs = {"-Xms512m", "-Xmx512m", "-XX:+UseG1GC"})
@State(Scope.Thread)
public class RejectedResponseBenchmarks {
  /** Distinct malformed success response paths. */
  @Param({"missing-model", "invalid-confidence"})
  public String rejection;

  private String body;
  private String expectedField;

  /** Prepare and validate the expected failure. */
  @Setup(Level.Trial)
  public void prepare() {
    body = Payloads.rejected(rejection);
    expectedField = rejection.equals("missing-model") ? "model" : "answers.department.confidence";
    if (!reject().fieldPath().equals(expectedField)) {
      throw new IllegalStateException("Unexpected rejected fixture path");
    }
  }

  /** Includes exception construction; expected failures never escape into the harness. */
  @Benchmark
  public JevResponseValidationException reject() {
    try {
      ResponseParser.parseSystemOne(200, Map.of(), body, "/v1/systemone");
    } catch (JevResponseValidationException expected) {
      return expected;
    }
    throw new IllegalStateException("Malformed fixture was accepted");
  }
}
