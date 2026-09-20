package net.codefinch.jev;

import java.util.Objects;
import java.util.Optional;

/**
 * One System One call: the state, the questions, and an optional per-call model override.
 *
 * <p>The wire {@code model} is resolved as: this request's {@code model}, else the client's default
 * model, else {@code TYPESAFE_DEFAULT_MODEL}, else {@code jev-latest}.
 *
 * @param state the shared input every question is evaluated against
 * @param questions the named questions
 * @param model a model name or alias overriding the client default, or empty
 */
public record SystemOneRequest(State state, Questions questions, Optional<String> model) {

  /** Validates the components. */
  public SystemOneRequest {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(questions, "questions");
    Objects.requireNonNull(model, "model");
    model.ifPresent(
        m -> {
          if (m.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
          }
        });
  }

  /** A request using the client's default model. */
  public static SystemOneRequest of(State state, Questions questions) {
    return new SystemOneRequest(state, questions, Optional.empty());
  }

  /** A copy of this request pinned to a model name or alias. */
  public SystemOneRequest withModel(String model) {
    return new SystemOneRequest(state, questions, Optional.of(model));
  }
}
