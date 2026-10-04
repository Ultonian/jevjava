package net.codefinch.jev.benchmarks.components;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.config.JvmSettings;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.model.Content;
import net.codefinch.jev.model.SystemOneRequest;
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
public class ConstructionBenchmarks {
  /** Content size and question-count changes relative to the representative case. */
  @Param({"ticket", "structured16k", "questions100"})
  public String scenario;

  private JsonNode input;
  private Payloads.QuestionInput questions;

  /** Prepare the caller's input tree outside measured construction. */
  @Setup(Level.Trial)
  public void prepare() {
    input = Payloads.content(scenario);
    questions = Payloads.questions(Payloads.count(scenario));
  }

  /** Measures defensive tree copying into immutable Content. */
  @Benchmark
  public Content content() {
    return Content.fromJson(input);
  }

  /** Builds content, state, questions and the request from prepared input. */
  @Benchmark
  public SystemOneRequest request() {
    return Payloads.request(input, questions);
  }
}
