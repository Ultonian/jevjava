package net.codefinch.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The answer to a {@link ChoiceQuestion}.
 *
 * @param choice the selected label
 * @param probabilities probability per label, in response order
 * @param confidence how concentrated the probability mass is, in {@code [0, 1]}
 */
public record ChoiceAnswer(String choice, Map<String, Double> probabilities, double confidence)
    implements Answer {

  /** Validates and snapshots the components. */
  public ChoiceAnswer {
    Objects.requireNonNull(choice, "choice");
    Objects.requireNonNull(probabilities, "probabilities");
    probabilities = Collections.unmodifiableMap(new LinkedHashMap<>(probabilities));
  }

  @Override
  public String type() {
    return "choice";
  }
}
