package net.codefinch.jev;

import java.util.Objects;
import java.util.Optional;

/**
 * A question that selects one of the labels in its criteria.
 *
 * @param instructions the question; see {@link Question#instructions()}
 * @param criteria the options; required by the API
 */
public record ChoiceQuestion(Optional<Content> instructions, ChoiceCriteria criteria)
    implements Question {

  /** Validates the components. */
  public ChoiceQuestion {
    Objects.requireNonNull(instructions, "instructions");
    Objects.requireNonNull(criteria, "criteria");
  }

  /** A choice from text instructions. */
  public static ChoiceQuestion of(String instructions, ChoiceCriteria criteria) {
    return new ChoiceQuestion(Optional.of(Content.of(instructions)), criteria);
  }

  /** A choice from content instructions. */
  public static ChoiceQuestion of(Content instructions, ChoiceCriteria criteria) {
    return new ChoiceQuestion(Optional.of(instructions), criteria);
  }

  @Override
  public String type() {
    return "choice";
  }
}
