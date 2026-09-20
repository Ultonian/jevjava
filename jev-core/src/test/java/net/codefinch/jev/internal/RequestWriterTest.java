package net.codefinch.jev.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.Fixtures;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Questions;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneRequest;
import org.junit.jupiter.api.Test;

class RequestWriterTest {

  @Test
  void quickstartRequestHasFixedFieldOrderAndShapes() throws JsonProcessingException {
    Questions q =
        Questions.builder()
            .noul("refund_requested", "Does `ticket` ask for money back?")
            .noul(
                "urgent", "Is `ticket` urgent?", NoulCriteria.of("Needs action today", "Can wait"))
            .choice(
                "department",
                "Which team handles `ticket`?",
                ChoiceCriteria.builder().option("billing", "Invoices").option("other").build())
            .score("severity", "How severe?", List.of("Cosmetic", "Degraded", "Blocking"))
            .build();
    SystemOneRequest request = SystemOneRequest.of(State.of(Map.of("ticket", "text")), q);
    String json = RequestWriter.write(request, "jev-latest");
    assertThat(json)
        .isEqualTo(
            "{\"model\":\"jev-latest\",\"state\":{\"ticket\":\"text\"},\"questions\":{\"refund_requested\":{\"type\":\"noul\",\"instructions\":\"Does"
                + " `ticket` ask for money"
                + " back?\"},\"urgent\":{\"type\":\"noul\",\"instructions\":\"Is `ticket`"
                + " urgent?\",\"criteria\":{\"true\":\"Needs action today\",\"false\":\"Can"
                + " wait\"}},\"department\":{\"type\":\"choice\",\"instructions\":\"Which team"
                + " handles"
                + " `ticket`?\",\"criteria\":{\"billing\":\"Invoices\",\"other\":null}},\"severity\":{\"type\":\"score\",\"instructions\":\"How"
                + " severe?\",\"criteria\":[\"Cosmetic\",\"Degraded\",\"Blocking\"]}}}");
  }

  @Test
  void perCallModelOverridesTheResolvedDefault() {
    SystemOneRequest request =
        SystemOneRequest.of(State.of("s"), Questions.of("n", NoulQuestion.of("?")));
    assertThat(
            RequestWriter.toJson(request.withModel("jev-preview"), "jev-latest")
                .get("model")
                .textValue())
        .isEqualTo("jev-preview");
  }

  /**
   * From typesafe-sdk-js test/client.test.ts "preserves null state, instructions, and criteria
   * values".
   */
  @Test
  void explicitNullsArePreservedEverywhereTheApiAllowsThem() throws JsonProcessingException {
    // The JS fixture also has state:null and a null score level; the API schema rejects both, so
    // the
    // Java builders cannot express them (documented divergence). Everything else is reproduced.
    Questions q =
        Questions.builder()
            .put("noul", NoulQuestion.of(Content.NULL, NoulCriteria.of(Content.NULL, Content.NULL)))
            .put("noCriteria", NoulQuestion.of(Content.NULL))
            .put(
                "choice",
                ChoiceQuestion.of(
                    Content.NULL, ChoiceCriteria.builder().option("yes").option("no").build()))
            .put(
                "score",
                ScoreQuestion.of(Content.NULL, List.of(Content.of("low"), Content.of("high"))))
            .build();
    JsonNode expected = Json.parse(Fixtures.read("requests/js-null-preservation.json"));
    JsonNode actual = RequestWriter.toJson(SystemOneRequest.of(State.of("s"), q), "jev-latest");
    assertThat(actual.get("questions").get("noul"))
        .isEqualTo(expected.get("questions").get("noul"));
    assertThat(actual.get("questions").get("choice"))
        .isEqualTo(expected.get("questions").get("choice"));
    // Java cannot send "criteria": null on a noul (omit is the only absent form) — see PARITY.md.
    assertThat(actual.get("questions").get("noCriteria").has("criteria")).isFalse();
    assertThat(actual.get("questions").get("noCriteria").get("instructions").isNull()).isTrue();
    assertThat(actual.get("questions").get("score").get("instructions").isNull()).isTrue();
  }

  /**
   * From typesafe-sdk-js test/client.test.ts "allows omitted instructions and preserves JSON
   * arrays".
   */
  @Test
  void omittedInstructionsAndNestedArraysWithNulls() throws JsonProcessingException {
    List<Object> stateList = Arrays.asList(null, Map.of("messages", List.of("hello")));
    Questions q =
        Questions.builder()
            .put(
                "choice",
                new ChoiceQuestion(
                    Optional.empty(),
                    ChoiceCriteria.of(Map.of("yes", Arrays.asList(null, Map.of("example", true))))))
            .put(
                "score",
                new ScoreQuestion(
                    Optional.empty(),
                    List.of(Content.of(Arrays.asList("low", null)), Content.of("high"))))
            .put(
                "arrayInstructions",
                NoulQuestion.of(
                    Content.of(Arrays.asList(null, Map.of("examples", List.of(1, false)))),
                    new NoulCriteria(
                        Optional.of(Content.of(Arrays.asList("yes", null))), Optional.empty())))
            .put("defaultInstructions", NoulQuestion.of(Content.NULL))
            .build();
    JsonNode expected =
        Json.parse(Fixtures.read("requests/js-omitted-instructions-and-arrays.json"));
    JsonNode actual =
        RequestWriter.toJson(SystemOneRequest.of(State.of(stateList), q), "jev-latest");
    assertThat(actual.get("state")).isEqualTo(expected.get("state"));
    for (String id : List.of("choice", "score", "arrayInstructions", "defaultInstructions")) {
      assertThat(actual.get("questions").get(id))
          .as(id)
          .isEqualTo(expected.get("questions").get(id));
    }
    assertThat(actual.get("questions").get("choice").has("instructions")).isFalse();
  }

  /**
   * From typesafe-sdk-python tests/test_questions.py "direct encoding omits only default fields".
   */
  @Test
  void pythonOmitsOnlyUnsetFieldsAndKeepsNestedNulls() throws JsonProcessingException {
    JsonNode choice =
        RequestWriter.toJson(
                SystemOneRequest.of(
                    State.of("x"),
                    Questions.of(
                        "q",
                        new ChoiceQuestion(Optional.empty(), ChoiceCriteria.of(nullValued("a"))))),
                "m")
            .get("questions")
            .get("q");
    assertThat(choice).isEqualTo(Json.parse("{\"type\":\"choice\",\"criteria\":{\"a\":null}}"));

    JsonNode noul =
        RequestWriter.toJson(
                SystemOneRequest.of(
                    State.of("x"),
                    Questions.of(
                        "q",
                        NoulQuestion.of(
                            Content.of(List.of()),
                            new NoulCriteria(Optional.of(Content.NULL), Optional.empty())))),
                "m")
            .get("questions")
            .get("q");
    assertThat(noul)
        .isEqualTo(
            Json.parse("{\"type\":\"noul\",\"instructions\":[],\"criteria\":{\"true\":null}}"));
  }

  private static Map<String, Object> nullValued(String key) {
    Map<String, Object> map = new HashMap<>();
    map.put(key, null);
    return map;
  }

  @Test
  void serialisesTheSameBytesAfterCallerMutatesItsInputs() {
    Map<String, Object> stateMap = new HashMap<>();
    stateMap.put("ticket", "original");
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("a", "desc");
    List<String> levels = new ArrayList<>(List.of("lo", "hi"));
    Questions q = Questions.builder().choice("c", "?", options).score("s", "?", levels).build();
    SystemOneRequest request = SystemOneRequest.of(State.of(stateMap), q);
    String first = RequestWriter.write(request, "m");
    stateMap.put("ticket", "changed");
    options.put("b", "late");
    levels.add("extra");
    assertThat(RequestWriter.write(request, "m")).isEqualTo(first);
    assertThat(first)
        .contains("\"ticket\":\"original\"")
        .doesNotContain("late")
        .doesNotContain("extra");
  }
}
