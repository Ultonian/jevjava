package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestionsTest {

  @Test
  void emptySetIsRejectedLikeBothUpstreamSdks() {
    assertThatThrownBy(() -> Questions.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one question");
    assertThatThrownBy(() -> Questions.of(Map.of())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void idsMustBeUniqueAndNonBlank() {
    assertThatThrownBy(() -> Questions.builder().noul("a", "?").noul("a", "??"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate question id");
    assertThatThrownBy(() -> Questions.builder().noul(" ", "?"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void insertionOrderIsKept() {
    Questions q =
        Questions.builder()
            .noul("z", "?")
            .choice("y", "?", ChoiceCriteria.builder().option("a").build())
            .score("x", "?", List.of("lo", "hi"))
            .build();
    assertThat(q.ids()).containsExactly("z", "y", "x");
    assertThat(q.size()).isEqualTo(3);
    assertThat(q.asMap().get("y")).isInstanceOf(ChoiceQuestion.class);
    assertThat(q).isEqualTo(Questions.of(q.asMap())).hasSameHashCodeAs(Questions.of(q.asMap()));
    assertThat(q.toString()).startsWith("Questions{");
    assertThat(Questions.of("only", NoulQuestion.of("?")).ids()).containsExactly("only");
  }

  @Test
  void questionTypesAndFactories() {
    assertThat(NoulQuestion.of("?").type()).isEqualTo("noul");
    assertThat(NoulQuestion.of("?").instructions()).contains(Content.of("?"));
    assertThat(NoulQuestion.of("?").criteria()).isEmpty();
    assertThat(NoulQuestion.of("?", NoulCriteria.of("y", "n")).criteria()).isPresent();
    assertThat(NoulQuestion.of(Content.NULL).instructions()).contains(Content.NULL);
    assertThat(NoulQuestion.of(Content.NULL, NoulCriteria.ofTrue("y")).criteria()).isPresent();
    ChoiceCriteria options = ChoiceCriteria.builder().option("a").build();
    assertThat(ChoiceQuestion.of("?", options).type()).isEqualTo("choice");
    assertThat(ChoiceQuestion.of(Content.of("?"), options).criteria()).isEqualTo(options);
    assertThat(ScoreQuestion.of("?", List.of("a", "b")).type()).isEqualTo("score");
    assertThat(
            ScoreQuestion.of(Content.of("?"), List.of(Content.of("a"), Content.of("b"))).criteria())
        .hasSize(2);
  }

  @Test
  void noulCriteriaFactories() {
    assertThat(NoulCriteria.of("y", "n").trueDescription()).contains(Content.of("y"));
    assertThat(NoulCriteria.of("y", "n").falseDescription()).contains(Content.of("n"));
    assertThat(NoulCriteria.of(Content.NULL, Content.NULL).trueDescription())
        .contains(Content.NULL);
    assertThat(NoulCriteria.ofTrue("y").falseDescription()).isEmpty();
    assertThat(NoulCriteria.ofFalse("n").trueDescription()).isEmpty();
    assertThat(NoulCriteria.ofFalse("n").falseDescription()).contains(Content.of("n"));
  }

  @Test
  void scoreNeedsAtLeastTwoNonNullLevels() {
    assertThatThrownBy(() -> ScoreQuestion.of("?", List.of("only")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 2 levels");
    assertThatThrownBy(
            () -> new ScoreQuestion(Optional.empty(), List.of(Content.of("a"), Content.NULL)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("level 1 must not be JSON null");
    // No upper bound client-side: the docs' 10 is a server limit.
    List<String> eleven = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      eleven.add("level " + i);
    }
    assertThat(ScoreQuestion.of("?", eleven).criteria()).hasSize(11);
  }

  @Test
  void scoreLevelsAreSnapshotted() {
    List<Content> levels = new ArrayList<>(List.of(Content.of("a"), Content.of("b")));
    ScoreQuestion q = new ScoreQuestion(Optional.empty(), levels);
    levels.add(Content.of("c"));
    assertThat(q.criteria()).hasSize(2);
    assertThatThrownBy(() -> q.criteria().add(Content.of("d")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void choiceCriteriaBuilderAndMapForm() {
    ChoiceCriteria built =
        ChoiceCriteria.builder()
            .option("billing", "Invoices")
            .option("other")
            .option("rich", Content.of(Map.of("what", "x")))
            .build();
    assertThat(built.options().keySet()).containsExactly("billing", "other", "rich");
    assertThat(built.options().get("other")).isSameAs(Content.NULL);

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("a", "desc");
    map.put("b", null);
    map.put("c", Map.of("k", "v"));
    map.put("d", List.of("x"));
    map.put("e", Content.of("pre"));
    ChoiceCriteria fromMap = ChoiceCriteria.of(map);
    assertThat(fromMap.options().keySet()).containsExactly("a", "b", "c", "d", "e");
    assertThat(fromMap.options().get("b")).isSameAs(Content.NULL);
    assertThat(fromMap.options().get("c")).isInstanceOf(Content.JsonObject.class);
    assertThat(fromMap.options().get("d")).isInstanceOf(Content.JsonArray.class);
    assertThat(fromMap.options().get("e")).isEqualTo(Content.of("pre"));
    assertThat(fromMap).isEqualTo(ChoiceCriteria.of(map)).hasSameHashCodeAs(ChoiceCriteria.of(map));
    assertThat(fromMap.toString()).startsWith("ChoiceCriteria{");
    assertThat(Questions.builder().choice("q", "?", map).build().size()).isEqualTo(1);

    Map<String, Object> hashMap = new HashMap<>();
    hashMap.put("only", null);
    assertThat(ChoiceCriteria.of(hashMap).options()).containsKey("only");
  }

  @Test
  void choiceCriteriaRejectsBadInput() {
    assertThatThrownBy(() -> ChoiceCriteria.builder().option("a").option("a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate option label");
    assertThatThrownBy(() -> ChoiceCriteria.builder().option(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("empty");
    assertThatThrownBy(() -> ChoiceCriteria.of(Map.of("a", 42)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Integer");
    assertThatThrownBy(() -> ChoiceCriteria.builder().build().options().put("x", Content.NULL))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void stateRejectsNullContent() {
    assertThatThrownBy(() -> State.of(Content.NULL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("state must not be null");
    assertThat(State.of("t").content()).isEqualTo(Content.of("t"));
    assertThat(State.of(Map.of("a", 1)).content()).isInstanceOf(Content.JsonObject.class);
    assertThat(State.of(List.of(1)).content()).isInstanceOf(Content.JsonArray.class);
  }

  @Test
  void requestModelOverride() {
    Questions q = Questions.of("n", NoulQuestion.of("?"));
    SystemOneRequest base = SystemOneRequest.of(State.of("s"), q);
    assertThat(base.model()).isEmpty();
    assertThat(base.withModel("jev-preview").model()).contains("jev-preview");
    assertThat(base.withModel("jev-preview").state()).isEqualTo(base.state());
    assertThatThrownBy(() -> base.withModel(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("model must not be blank");
  }
}
