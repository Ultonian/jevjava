package net.codefinch.jev.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.codefinch.jev.Answer;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.JevAnswerTypeException;
import net.codefinch.jev.JevMissingAnswerException;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PatternsTest {

  private static SystemOneResponse response(Map<String, ? extends Answer> answers) {
    return new SystemOneResponse(
        "jev-1.13.0", Answers.of(answers), new Usage(1, 1), ResponseMetadata.of(Map.of(), "{}"));
  }

  private static ScoreAnswer score(double score, int levels, double confidence) {
    Map<Integer, Content> legend = new LinkedHashMap<>();
    Map<Integer, Double> probabilities = new LinkedHashMap<>();
    for (int i = 0; i < levels; i++) {
      legend.put(i, Content.of("level " + i));
      probabilities.put(i, i == 0 ? 1.0 : 0.0);
    }
    return new ScoreAnswer(score, legend, probabilities, confidence);
  }

  // ---- ConfidenceGate ---------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "0.0, ESCALATE",
    "0.49, ESCALATE",
    "0.5, CONFIRM",
    "0.89, CONFIRM",
    "0.9, ACT",
    "1.0, ACT"
  })
  void confidenceGateBoundariesAreInclusive(double confidence, ConfidenceGate.Decision expected) {
    ConfidenceGate gate = ConfidenceGate.of(0.5, 0.9);
    assertThat(gate.decide(confidence)).isEqualTo(expected);
    assertThat(gate.decide(new ChoiceAnswer("a", Map.of("a", 1.0), confidence)))
        .isEqualTo(expected);
    assertThat(gate.decide(score(1.0, 3, confidence))).isEqualTo(expected);
  }

  @Test
  void confidenceGateHasNoDefaultsAndValidates() {
    assertThatThrownBy(() -> ConfidenceGate.of(0.9, 0.5)).hasMessageContaining("must not exceed");
    assertThatThrownBy(() -> ConfidenceGate.of(-0.1, 0.5)).hasMessageContaining("low");
    assertThatThrownBy(() -> ConfidenceGate.of(0.5, 1.5)).hasMessageContaining("high");
    assertThatThrownBy(() -> ConfidenceGate.of(Double.NaN, 0.5)).hasMessageContaining("low");
    assertThatThrownBy(() -> ConfidenceGate.of(0.1, Double.POSITIVE_INFINITY))
        .hasMessageContaining("high");
    assertThatThrownBy(() -> ConfidenceGate.of(0.1, 0.5).decide(Double.NaN))
        .hasMessageContaining("confidence");
    assertThatThrownBy(() -> ConfidenceGate.of(0.1, 0.5).decide(1.01))
        .hasMessageContaining("confidence");
    assertThat(ConfidenceGate.of(0.5, 0.5).decide(0.5)).isEqualTo(ConfidenceGate.Decision.ACT);
    assertThat(ConfidenceGate.of(0.0, 1.0).decide(0.0)).isEqualTo(ConfidenceGate.Decision.CONFIRM);
  }

  // ---- NoulThreshold ----------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({"0.0, NO", "0.2, NO", "0.21, UNSURE", "0.79, UNSURE", "0.8, YES", "1.0, YES"})
  void noulThresholdBoundariesAreInclusive(double p, NoulThreshold.Decision expected) {
    NoulThreshold t = NoulThreshold.of(0.2, 0.8);
    assertThat(t.decide(p)).isEqualTo(expected);
    assertThat(t.decide(new NoulAnswer(p))).isEqualTo(expected);
  }

  @Test
  void noulThresholdSingleCutoffAndValidation() {
    NoulThreshold at = NoulThreshold.at(0.5);
    assertThat(at.decide(0.5)).isEqualTo(NoulThreshold.Decision.YES);
    assertThat(at.decide(0.49)).isEqualTo(NoulThreshold.Decision.NO);
    assertThat(NoulThreshold.at(0.0).decide(0.0)).isEqualTo(NoulThreshold.Decision.YES);
    assertThatThrownBy(() -> NoulThreshold.of(0.8, 0.2)).hasMessageContaining("must not exceed");
    assertThatThrownBy(() -> NoulThreshold.at(Double.NaN)).hasMessageContaining("cutoff");
    assertThatThrownBy(() -> NoulThreshold.of(0.2, 0.8).decide(-0.5))
        .hasMessageContaining("probability");
  }

  // ---- Composite --------------------------------------------------------------------------

  @Test
  void compositeIsNormalisedWeightedAverage() {
    Composite composite = Composite.score(Map.of("severity", 2.0, "impact", 1.0));
    Answers answers =
        Answers.of(
            Map.of(
                "severity",
                score(2.0, 3, 0.9),
                "impact",
                score(1.0, 5, 0.9),
                "other",
                new NoulAnswer(0.1)));
    // severity 2/2 = 1.0 (weight 2), impact 1/4 = 0.25 (weight 1) -> (2 + 0.25) / 3
    assertThat(composite.apply(answers)).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(composite.weights()).containsKeys("severity", "impact");
    assertThat(Composite.normalised(score(0.0, 1, 1.0))).as("single-level rubric").isZero();
    assertThat(Composite.normalised(score(5.0, 3, 1.0))).as("clamped").isEqualTo(1.0);
  }

  /** Phase 3 review P2: accepted finite weights must never overflow or underflow the mean. */
  @Test
  void compositeIsScaleInvariantAndFiniteAtBothExtremes() {
    Answers half = Answers.of(Map.of("a", score(0.5, 2, 1), "b", score(0.5, 2, 1)));
    Answers full = Answers.of(Map.of("a", score(1.0, 2, 1), "b", score(1.0, 2, 1)));
    for (double w : new double[] {1.0, Double.MAX_VALUE, Double.MIN_VALUE, 1e-300, 1e300}) {
      Composite c = Composite.score(Map.of("a", w, "b", w));
      assertThat(c.apply(half)).as("weights " + w).isEqualTo(0.5);
      assertThat(c.apply(full)).as("weights " + w).isEqualTo(1.0);
    }
    Composite mixed = Composite.score(Map.of("a", Double.MAX_VALUE, "b", 1.0));
    assertThat(mixed.apply(Answers.of(Map.of("a", score(1.0, 2, 1), "b", score(0.0, 2, 1)))))
        .isEqualTo(1.0);
    Composite ratio = Composite.score(Map.of("a", 3e300, "b", 1e300));
    assertThat(ratio.apply(Answers.of(Map.of("a", score(1.0, 2, 1), "b", score(0.0, 2, 1)))))
        .isEqualTo(0.75);
    Composite tiny = Composite.score(Map.of("a", 3e-300, "b", 1e-300));
    assertThat(tiny.apply(Answers.of(Map.of("a", score(1.0, 2, 1), "b", score(0.0, 2, 1)))))
        .isEqualTo(0.75);
  }

  @Test
  void compositeValidation() {
    assertThatThrownBy(() -> Composite.score(Map.of())).hasMessageContaining("at least one");
    assertThatThrownBy(() -> Composite.score(Map.of("a", 0.0))).hasMessageContaining("positive");
    assertThatThrownBy(() -> Composite.score(Map.of("a", -1.0))).hasMessageContaining("positive");
    assertThatThrownBy(() -> Composite.score(Map.of("a", Double.NaN)))
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> Composite.score(Map.of("a", Double.POSITIVE_INFINITY)))
        .hasMessageContaining("finite");
    Composite c = Composite.score(Map.of("a", 1.0));
    assertThatThrownBy(() -> c.apply(Answers.of(Map.of("b", score(1, 2, 1)))))
        .isInstanceOf(JevMissingAnswerException.class);
    assertThatThrownBy(() -> c.apply(Answers.of(Map.of("a", new NoulAnswer(0.5)))))
        .isInstanceOf(JevAnswerTypeException.class);
    assertThatThrownBy(() -> c.weights().put("x", 1.0))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- FanOut -----------------------------------------------------------------------------

  @Test
  void fanOutBindsEachItemStructurallyIntoItsInstructions() {
    List<String> tickets = List.of("Refund me now", "Where is my order?", "Thanks!");
    FanOut<String> fan =
        FanOut.noul(
            tickets,
            FanOut.text(),
            "Is the message asking for money back?",
            NoulCriteria.of("yes", "no"));
    assertThat(fan.items()).containsExactlyElementsOf(tickets);
    assertThat(fan.questions().ids()).containsExactly("item_0", "item_1", "item_2");
    NoulQuestion q1 = (NoulQuestion) fan.questions().asMap().get("item_1");
    JsonNode instructions = q1.instructions().orElseThrow().toJson();
    assertThat(instructions.get("task").textValue())
        .isEqualTo("Is the message asking for money back?");
    assertThat(instructions.get("item").textValue()).isEqualTo("Where is my order?");
    assertThat(q1.criteria()).isPresent();
    assertThatThrownBy(() -> fan.items().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void fanOutChoiceAndScoreVariantsAndStructuredItems() {
    record Order(String id, int amount) {}

    List<Order> orders = List.of(new Order("A", 10), new Order("B", 200));
    FanOut<Order> choice =
        FanOut.choice(
            orders,
            o -> Content.of(Map.of("id", o.id(), "amount", o.amount())),
            "Which team?",
            ChoiceCriteria.builder().option("billing").option("other").build());
    ChoiceQuestion cq = (ChoiceQuestion) choice.questions().asMap().get("item_1");
    assertThat(cq.instructions().orElseThrow().toJson().get("item").get("amount").intValue())
        .isEqualTo(200);
    assertThat(cq.criteria().options()).containsKeys("billing", "other");
    FanOut<Order> score =
        FanOut.score(orders, o -> Content.of(o.id()), "How risky?", List.of("low", "high"));
    ScoreQuestion sq = (ScoreQuestion) score.questions().asMap().get("item_0");
    assertThat(sq.criteria()).hasSize(2);
    assertThat(FanOut.noul(orders, o -> Content.of(o.id()), "Any?").questions().size())
        .isEqualTo(2);
  }

  @Test
  void fanOutMapsAnswersBackInItemOrderAndTypedAccessorsWork() {
    List<String> items = List.of("a", "b", "c");
    FanOut<String> fan = FanOut.noul(items, FanOut.text(), "?");
    SystemOneResponse r =
        response(Map.of("item_0", new NoulAnswer(0.9), "item_2", new NoulAnswer(0.1)));
    List<ItemAnswer<String>> answers = fan.answers(r);
    assertThat(answers).extracting(ItemAnswer::item).containsExactly("a", "b", "c");
    assertThat(answers).extracting(ItemAnswer::index).containsExactly(0, 1, 2);
    assertThat(answers.get(0).noul().noul()).isEqualTo(0.9);
    assertThat(answers.get(1).answer()).as("dropped or unknown answer").isEmpty();
    assertThatThrownBy(() -> answers.get(1).noul()).isInstanceOf(JevMissingAnswerException.class);
    assertThatThrownBy(() -> answers.get(2).choice()).isInstanceOf(JevAnswerTypeException.class);
    assertThatThrownBy(() -> answers.get(2).score()).isInstanceOf(JevAnswerTypeException.class);
    assertThat(answers.get(2).id()).isEqualTo("item_2");
    assertThat(FanOut.id(7)).isEqualTo("item_7");
  }

  @Test
  void fanOutValidation() {
    assertThatThrownBy(() -> FanOut.noul(List.of(), FanOut.text(), "?"))
        .hasMessageContaining("at least one item");
    assertThatThrownBy(() -> FanOut.noul(List.of("a"), s -> Content.NULL, "?"))
        .hasMessageContaining("JSON null");
    assertThatThrownBy(() -> FanOut.noul(List.of("a"), s -> null, "?"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> FanOut.score(List.of("a"), FanOut.text(), "?", List.of("only")))
        .hasMessageContaining("at least 2");
    assertThat(Optional.of(1))
        .isPresent(); // keep Optional import used for readability of the item record
  }
}
