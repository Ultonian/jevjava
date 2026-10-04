package net.codefinch.jev.benchmarks.components;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.config.JvmSettings;
import net.codefinch.jev.model.Answer;
import net.codefinch.jev.model.Answers;
import net.codefinch.jev.model.Content;
import net.codefinch.jev.model.ScoreAnswer;
import net.codefinch.jev.patterns.Composite;
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
public class CompositeBenchmarks {
  /** Weighted question counts. */
  @Param({"1", "10", "100"})
  public int count;

  private Composite composite = Composite.score(Map.of("q0", 1.0));
  private Answers answers;

  /** Prepare varying finite scores and weights, avoiding a constant-only benchmark body. */
  @Setup(Level.Trial)
  public void prepare() {
    Map<String, Double> weights = new LinkedHashMap<>();
    Map<String, Answer> values = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      weights.put("q" + i, (double) (i % 3 + 1));
      values.put(
          "q" + i,
          new ScoreAnswer(
              i % 3,
              Map.of(0, Content.of("low"), 1, Content.of("middle"), 2, Content.of("high")),
              Map.of(0, 0.2, 1, 0.3, 2, 0.5),
              0.7));
    }
    composite = Composite.score(weights);
    answers = Answers.of(values);
  }

  /** Computes the normalized weighted score over prepared answers. */
  @Benchmark
  public double apply() {
    return composite.apply(answers);
  }
}
