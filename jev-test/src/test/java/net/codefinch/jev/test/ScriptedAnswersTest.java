package net.codefinch.jev.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.codefinch.jev.model.ChoiceAnswer;
import net.codefinch.jev.model.ChoiceQuestion;
import net.codefinch.jev.model.Content;
import net.codefinch.jev.model.Questions;
import net.codefinch.jev.model.ScoreQuestion;
import org.junit.jupiter.api.Test;

/** Question-aware overrides preserve schemas without requiring callers to downcast questions. */
class ScriptedAnswersTest {
  private static final Questions QUESTIONS =
      Questions.builder()
          .choice("department", "Route", Map.of("billing", "Payments", "support", "Help"))
          .score("severity", "Severity", List.of("low", "medium", "high"))
          .noul("refund", "Refund?")
          .build();

  @Test
  void overridesUseRetainedLabelsAndRubricWithoutChangingEarlierResponses() {
    ScriptedAnswers script = ScriptedAnswers.neutral(QUESTIONS);
    var before = script.build();
    var after =
        script.choice("department", "support", 0.8, 0.9).score("severity", 1.8, 0.7).build();
    assertThat(after.answers().choice("department").probabilities())
        .containsOnlyKeys("billing", "support")
        .containsEntry("support", 0.8);
    assertThat(after.answers().score("severity").legend())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(0, Content.of("low"), 1, Content.of("medium"), 2, Content.of("high")));
    assertThat(after.answers().score("severity").score()).isEqualTo(1.8);
    assertThat(before.answers().score("severity").score()).isEqualTo(1.0);
    assertThat(QUESTIONS.asMap()).hasSize(3);
    assertThat(
            script
                .without("severity")
                .score("severity", 0.0, 1.0)
                .answers()
                .score("severity")
                .score())
        .isZero();
  }

  @Test
  void missingContextWrongTypesAndUnknownLabelsHaveClearFailures() {
    ScriptedAnswers script = ScriptedAnswers.neutral(QUESTIONS);
    for (String id : List.of("missing", "refund", "severity")) {
      assertThatThrownBy(() -> script.choice(id, "support", 0.8, 0.9))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("choice question");
    }
    for (String id : List.of("missing", "refund", "department")) {
      assertThatThrownBy(() -> script.score(id, 1.0, 0.9))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("score question");
    }
    assertThatThrownBy(() -> script.choice("department", "unknown", 0.8, 0.9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an option");
    assertThatThrownBy(() -> ScriptedAnswers.empty().choice("department", "support", 0.8, 0.9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no retained");
    assertThatThrownBy(() -> ScriptedAnswers.empty().score("severity", 1.0, 0.9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no retained");
  }

  @Test
  void explicitQuestionsAndMalformedFixturesRemainAvailable() {
    var explicit =
        ScriptedAnswers.empty()
            .choice(
                "department",
                (ChoiceQuestion) QUESTIONS.asMap().get("department"),
                "support",
                0.8,
                0.9)
            .score("severity", (ScoreQuestion) QUESTIONS.asMap().get("severity"), 1.8, 0.7)
            .answers();
    var inferred =
        ScriptedAnswers.neutral(QUESTIONS)
            .choice("department", "support", 0.8, 0.9)
            .score("severity", 1.8, 0.7)
            .answers();
    assertThat(inferred.choice("department")).isEqualTo(explicit.choice("department"));
    assertThat(inferred.score("severity")).isEqualTo(explicit.score("severity"));
    var malformed = new ChoiceAnswer("unrecognised", Map.of("unrecognised", 2.0), -1.0);
    assertThat(ScriptedAnswers.empty().put("department", malformed).answers().choice("department"))
        .isEqualTo(malformed);
    assertThat(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("severity", 99.0, -1.0)
                .answers()
                .score("severity")
                .score())
        .isEqualTo(99.0);
  }
}
