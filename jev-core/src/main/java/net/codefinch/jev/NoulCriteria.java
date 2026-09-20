package net.codefinch.jev;

import java.util.Objects;
import java.util.Optional;

/**
 * Optional descriptions of what counts as a yes ({@code true}) and a no ({@code false}) for a
 * {@link NoulQuestion}. Either side may be omitted, as the API schema allows.
 *
 * @param trueDescription description of a yes; empty omits the key, {@link Content#NULL} sends null
 * @param falseDescription description of a no; empty omits the key, {@link Content#NULL} sends null
 */
public record NoulCriteria(Optional<Content> trueDescription, Optional<Content> falseDescription) {

  /** Validates the components. */
  public NoulCriteria {
    Objects.requireNonNull(trueDescription, "trueDescription");
    Objects.requireNonNull(falseDescription, "falseDescription");
  }

  /** Both descriptions as text. */
  public static NoulCriteria of(String trueDescription, String falseDescription) {
    return new NoulCriteria(
        Optional.of(Content.of(trueDescription)), Optional.of(Content.of(falseDescription)));
  }

  /** Both descriptions as content. */
  public static NoulCriteria of(Content trueDescription, Content falseDescription) {
    return new NoulCriteria(Optional.of(trueDescription), Optional.of(falseDescription));
  }

  /** Only the yes side described. */
  public static NoulCriteria ofTrue(String trueDescription) {
    return new NoulCriteria(Optional.of(Content.of(trueDescription)), Optional.empty());
  }

  /** Only the no side described. */
  public static NoulCriteria ofFalse(String falseDescription) {
    return new NoulCriteria(Optional.empty(), Optional.of(Content.of(falseDescription)));
  }
}
