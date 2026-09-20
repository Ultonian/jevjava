package net.codefinch.jev;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The answer to a {@link ScoreQuestion}.
 *
 * @param score the probability-weighted position on the levels; may fall between integers
 * @param legend the requested level descriptions keyed by level number, so the score can be read
 * @param probabilities probability per level number
 * @param confidence how concentrated the probability mass is, in {@code [0, 1]}
 */
public record ScoreAnswer(
    double score,
    Map<Integer, Content> legend,
    Map<Integer, Double> probabilities,
    double confidence)
    implements Answer {

  /** Validates and snapshots the components, ordering both maps by level. */
  public ScoreAnswer {
    Objects.requireNonNull(legend, "legend");
    Objects.requireNonNull(probabilities, "probabilities");
    legend = Collections.unmodifiableMap(new TreeMap<>(legend));
    probabilities = Collections.unmodifiableMap(new TreeMap<>(probabilities));
  }

  @Override
  public String type() {
    return "score";
  }

  /** The highest level number, i.e. {@code legend.size() - 1}. */
  public int topLevel() {
    return legend.isEmpty() ? 0 : Collections.max(legend.keySet());
  }
}
