package net.codefinch.jev;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The answers of one response, keyed by the question ids of the request, in response order.
 *
 * <p>Typed accessors throw {@link JevMissingAnswerException} when the id is absent (for example
 * when the server returned an answer kind this SDK does not know) and {@link
 * JevAnswerTypeException} when the answer is of a different primitive; {@link #get(String)} does
 * neither.
 */
public final class Answers {
  private final Map<String, Answer> byId;

  private Answers(Map<String, Answer> byId) {
    this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
  }

  /** Answers from an ordered map. */
  public static Answers of(Map<String, ? extends Answer> answers) {
    Objects.requireNonNull(answers, "answers");
    Map<String, Answer> copy = new LinkedHashMap<>();
    answers.forEach(
        (id, answer) ->
            copy.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(answer, "answer")));
    return new Answers(copy);
  }

  /** The answer for an id, if present. */
  public Optional<Answer> get(String id) {
    return Optional.ofNullable(byId.get(id));
  }

  /** The noul answer for an id. */
  public NoulAnswer noul(String id) {
    return typed(id, NoulAnswer.class);
  }

  /** The choice answer for an id. */
  public ChoiceAnswer choice(String id) {
    return typed(id, ChoiceAnswer.class);
  }

  /** The score answer for an id. */
  public ScoreAnswer score(String id) {
    return typed(id, ScoreAnswer.class);
  }

  /** All answers by id, in response order. Unmodifiable. */
  public Map<String, Answer> asMap() {
    return byId;
  }

  /** The ids in response order. */
  public Set<String> ids() {
    return byId.keySet();
  }

  /** Number of answers. */
  public int size() {
    return byId.size();
  }

  private <A extends Answer> A typed(String id, Class<A> expected) {
    Answer answer = byId.get(id);
    if (answer == null) {
      throw new JevMissingAnswerException(id);
    }
    if (!expected.isInstance(answer)) {
      throw new JevAnswerTypeException(id, expected, answer.getClass());
    }
    return expected.cast(answer);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Answers other && byId.equals(other.byId);
  }

  @Override
  public int hashCode() {
    return byId.hashCode();
  }

  @Override
  public String toString() {
    return "Answers" + byId;
  }
}
