package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswersTest {
  private static final Answers ANSWERS =
      Answers.of(
          Map.of(
              "n", new NoulAnswer(0.5),
              "c", new ChoiceAnswer("a", Map.of("a", 1.0), 1.0),
              "s",
                  new ScoreAnswer(
                      1.0,
                      Map.of(0, Content.of("lo"), 1, Content.of("hi")),
                      Map.of(0, 0.0, 1, 1.0),
                      1.0)));

  @Test
  void typedAccessorsReturnTheRightKind() {
    assertThat(ANSWERS.noul("n").noul()).isEqualTo(0.5);
    assertThat(ANSWERS.choice("c").choice()).isEqualTo("a");
    assertThat(ANSWERS.score("s").score()).isEqualTo(1.0);
    assertThat(ANSWERS.get("n")).containsInstanceOf(NoulAnswer.class);
    assertThat(ANSWERS.get("missing")).isEmpty();
    assertThat(ANSWERS.size()).isEqualTo(3);
    assertThat(ANSWERS.ids()).containsExactlyInAnyOrder("n", "c", "s");
    assertThat(ANSWERS.toString()).startsWith("Answers{");
    assertThat(ANSWERS)
        .isEqualTo(Answers.of(ANSWERS.asMap()))
        .hasSameHashCodeAs(Answers.of(ANSWERS.asMap()));
  }

  @Test
  void wrongKindThrowsWithBothTypeNames() {
    assertThatThrownBy(() -> ANSWERS.choice("n"))
        .isInstanceOf(JevAnswerTypeException.class)
        .hasMessage("answer 'n' is a NoulAnswer, not a ChoiceAnswer")
        .satisfies(
            e -> {
              JevAnswerTypeException t = (JevAnswerTypeException) e;
              assertThat(t.id()).isEqualTo("n");
              assertThat(t.expected()).isEqualTo("ChoiceAnswer");
              assertThat(t.actual()).isEqualTo("NoulAnswer");
            });
    assertThatThrownBy(() -> ANSWERS.score("c")).isInstanceOf(JevAnswerTypeException.class);
    assertThatThrownBy(() -> ANSWERS.noul("s")).isInstanceOf(JevAnswerTypeException.class);
  }

  @Test
  void missingIdThrows() {
    assertThatThrownBy(() -> ANSWERS.noul("nope"))
        .isInstanceOf(JevMissingAnswerException.class)
        .hasMessage("no answer for question id 'nope'")
        .satisfies(e -> assertThat(((JevMissingAnswerException) e).id()).isEqualTo("nope"));
  }

  @Test
  void orderIsKeptAndTopLevelOfEmptyLegendIsZero() {
    Map<String, Answer> ordered = new LinkedHashMap<>();
    ordered.put("z", new NoulAnswer(0));
    ordered.put("a", new NoulAnswer(1));
    assertThat(Answers.of(ordered).ids()).containsExactly("z", "a");
    assertThat(new ScoreAnswer(0, Map.of(), Map.of(), 0).topLevel()).isZero();
    assertThat(new NoulAnswer(0.1).type()).isEqualTo("noul");
  }
}
