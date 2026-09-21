package net.codefinch.jev.patterns;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ScoreAnswer;

/**
 * A normalised weighted sum of Score answers: each score is divided by its top level (so every
 * question contributes in {@code [0, 1]}), multiplied by its weight, summed, and divided by the sum
 * of the weights. The result is in {@code [0, 1]} and comparable across items that were scored with
 * the same questions.
 */
public final class Composite {
  private final Map<String, Double> weights;

  private Composite(Map<String, Double> weights) {
    this.weights = Collections.unmodifiableMap(new LinkedHashMap<>(weights));
  }

  /**
   * A composite over the given question ids and positive, finite weights.
   *
   * @throws IllegalArgumentException if empty, or a weight is not positive and finite
   */
  public static Composite score(Map<String, Double> weights) {
    Objects.requireNonNull(weights, "weights");
    if (weights.isEmpty()) {
      throw new IllegalArgumentException("at least one weight is required");
    }
    weights.forEach(
        (id, w) -> {
          Objects.requireNonNull(id, "id");
          if (w == null || !(w > 0) || Double.isInfinite(w)) {
            throw new IllegalArgumentException(
                "weight for '" + id + "' must be positive and finite");
          }
        });
    return new Composite(weights);
  }

  /** The question ids and weights, in the order given. */
  public Map<String, Double> weights() {
    return weights;
  }

  /**
   * The composite value for a response's answers.
   *
   * @throws net.codefinch.jev.JevMissingAnswerException if a weighted id is absent
   * @throws net.codefinch.jev.JevAnswerTypeException if a weighted id is not a score answer
   */
  public double apply(Answers answers) {
    Objects.requireNonNull(answers, "answers");
    double total = 0;
    double weightSum = 0;
    for (Map.Entry<String, Double> e : weights.entrySet()) {
      ScoreAnswer score = answers.score(e.getKey());
      total += e.getValue() * normalised(score);
      weightSum += e.getValue();
    }
    return total / weightSum;
  }

  /** {@code score / topLevel}, or 0 for a single-level rubric (which cannot spread). */
  public static double normalised(ScoreAnswer answer) {
    int top = answer.topLevel();
    return top == 0 ? 0.0 : Math.min(1.0, Math.max(0.0, answer.score() / top));
  }
}
