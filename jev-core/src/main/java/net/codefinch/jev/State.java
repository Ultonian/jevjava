package net.codefinch.jev;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The input every question in a request is evaluated against: text, a JSON object or a JSON array.
 * Unlike other content positions, the API does not accept {@code null} state.
 *
 * @param content the state content; never {@link Content#NULL}
 */
public record State(Content content) {

  /** Validates that the content is not JSON {@code null}. */
  public State {
    Objects.requireNonNull(content, "content");
    if (content.isNull()) {
      throw new IllegalArgumentException("state must not be null content");
    }
  }

  /** Text state. */
  public static State of(String text) {
    return new State(Content.of(text));
  }

  /** Object state; prefer named fields when the context has several parts. */
  public static State of(Map<String, ?> object) {
    return new State(Content.of(object));
  }

  /** Array state. */
  public static State of(List<?> array) {
    return new State(Content.of(array));
  }

  /** State from existing content. */
  public static State of(Content content) {
    return new State(content);
  }
}
