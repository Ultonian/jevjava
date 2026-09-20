package net.codefinch.jev;

import java.util.Objects;
import java.util.Optional;

/**
 * A yes/no question, answered with the probability of yes.
 *
 * @param instructions the question; see {@link Question#instructions()}
 * @param criteria optional yes/no descriptions; empty omits the {@code criteria} key
 */
public record NoulQuestion(Optional<Content> instructions, Optional<NoulCriteria> criteria)
    implements Question {

  /** Validates the components. */
  public NoulQuestion {
    Objects.requireNonNull(instructions, "instructions");
    Objects.requireNonNull(criteria, "criteria");
  }

  /** A noul from text instructions. */
  public static NoulQuestion of(String instructions) {
    return new NoulQuestion(Optional.of(Content.of(instructions)), Optional.empty());
  }

  /** A noul from text instructions with criteria. */
  public static NoulQuestion of(String instructions, NoulCriteria criteria) {
    return new NoulQuestion(Optional.of(Content.of(instructions)), Optional.of(criteria));
  }

  /** A noul from content instructions. */
  public static NoulQuestion of(Content instructions) {
    return new NoulQuestion(Optional.of(instructions), Optional.empty());
  }

  /** A noul from content instructions with criteria. */
  public static NoulQuestion of(Content instructions, NoulCriteria criteria) {
    return new NoulQuestion(Optional.of(instructions), Optional.of(criteria));
  }

  @Override
  public String type() {
    return "noul";
  }
}
