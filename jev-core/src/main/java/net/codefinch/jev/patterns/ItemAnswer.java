package net.codefinch.jev.patterns;

import java.util.Optional;
import net.codefinch.jev.exception.JevAnswerTypeException;
import net.codefinch.jev.exception.JevMissingAnswerException;
import net.codefinch.jev.model.Answer;
import net.codefinch.jev.model.ChoiceAnswer;
import net.codefinch.jev.model.NoulAnswer;
import net.codefinch.jev.model.ScoreAnswer;

/**
 * One item of a {@link FanOut} with its answer.
 *
 * @param <T> the item type
 * @param item the item
 * @param index its position in the fan-out
 * @param id the question id the fan-out used for it
 * @param answer the answer, or empty if the server returned none for this id
 * @since 0.1.0
 */
public record ItemAnswer<T>(T item, int index, String id, Optional<Answer> answer) {

  /**
   * The noul answer.
   *
   * @throws JevMissingAnswerException if no answer is present for this id
   * @throws JevAnswerTypeException if the answer is not a noul answer
   */
  public NoulAnswer noul() {
    return typed(NoulAnswer.class);
  }

  /**
   * The choice answer.
   *
   * @throws JevMissingAnswerException if no answer is present for this id
   * @throws JevAnswerTypeException if the answer is not a choice answer
   */
  public ChoiceAnswer choice() {
    return typed(ChoiceAnswer.class);
  }

  /**
   * The score answer.
   *
   * @throws JevMissingAnswerException if no answer is present for this id
   * @throws JevAnswerTypeException if the answer is not a score answer
   */
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
