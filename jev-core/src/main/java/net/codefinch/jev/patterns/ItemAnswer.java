package net.codefinch.jev.patterns;

import java.util.Optional;
import net.codefinch.jev.Answer;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.JevAnswerTypeException;
import net.codefinch.jev.JevMissingAnswerException;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.ScoreAnswer;

/**
 * One item of a {@link FanOut} with its answer.
 *
 * @param <T> the item type
 * @param item the item
 * @param index its position in the fan-out
 * @param id the question id the fan-out used for it
 * @param answer the answer, or empty if the server returned none for this id
 */
public record ItemAnswer<T>(T item, int index, String id, Optional<Answer> answer) {

  /** The noul answer. */
  public NoulAnswer noul() {
    return typed(NoulAnswer.class);
  }

  /** The choice answer. */
  public ChoiceAnswer choice() {
    return typed(ChoiceAnswer.class);
  }

  /** The score answer. */
  public ScoreAnswer score() {
    return typed(ScoreAnswer.class);
  }

  private <A extends Answer> A typed(Class<A> expected) {
    Answer a = answer.orElseThrow(() -> new JevMissingAnswerException(id));
    if (!expected.isInstance(a)) {
      throw new JevAnswerTypeException(id, expected, a.getClass());
    }
    return expected.cast(a);
  }
}
