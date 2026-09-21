package net.codefinch.jev.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevRateLimitException;
import net.codefinch.jev.ModelList;
import net.codefinch.jev.ModelMetadata;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Questions;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;
import org.junit.jupiter.api.Test;

class RecordingJevClientTest {
  private static final Questions QUESTIONS =
      Questions.builder()
          .noul("refund", "Money back?")
          .choice(
              "dept",
              "Which team?",
              ChoiceCriteria.builder().option("billing").option("support").option("other").build())
          .score("severity", "How bad?", List.of("low", "mid", "high"))
          .build();
  private static final SystemOneRequest REQUEST =
      SystemOneRequest.of(State.of("ticket"), QUESTIONS);

  @Test
  void neutralAnswersCoverEveryQuestionShapedFromIt() {
    try (RecordingJevClient c = new RecordingJevClient()) {
      SystemOneResponse r = c.systemOne(REQUEST);
      assertThat(r.model()).isEqualTo("jev-test");
      assertThat(r.answers().noul("refund").noul()).isEqualTo(0.5);
      assertThat(r.answers().choice("dept").choice()).isEqualTo("billing");
      assertThat(r.answers().choice("dept").probabilities())
          .containsOnlyKeys("billing", "support", "other");
      assertThat(r.answers().score("severity").score()).isEqualTo(1.0);
      assertThat(r.answers().score("severity").legend()).hasSize(3);
      assertThat(r.answers().score("severity").topLevel()).isEqualTo(2);
      assertThat(r.rawBody()).contains("\"model\":\"jev-test\"");
    }
  }

  @Test
  void scriptedResponsesAreConsumedInOrderThenTheDefaultApplies() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-09-21T12:00:00Z"), ZoneOffset.UTC);
    try (RecordingJevClient c = new RecordingJevClient(clock)) {
      ChoiceQuestion dept = (ChoiceQuestion) QUESTIONS.asMap().get("dept");
      ScoreQuestion sev = (ScoreQuestion) QUESTIONS.asMap().get("severity");
      c.enqueue(
              ScriptedAnswers.neutral(QUESTIONS)
                  .noul("refund", 0.95)
                  .choice("dept", dept, "support", 0.8, 0.7)
                  .score("severity", sev, 2.0, 0.9)
                  .model("jev-1.13.0")
                  .usage(10, 2)
                  .headers(Map.of("x-typesafe-request-id", List.of("r1"))))
          .enqueueFailure(new JevRateLimitException(Map.of(), "", null))
          .enqueueFailure(new JevRateLimitException(Map.of(), "", null))
          .enqueue(req -> ScriptedAnswers.neutral(req.questions()).noul("refund", 0.01).build());
      assertThat(c.pendingScript()).isEqualTo(4);

      SystemOneResponse first =
          c.systemOne(REQUEST, RequestOptions.builder().header("x-test", "1").build());
      assertThat(first.answers().noul("refund").noul()).isEqualTo(0.95);
      assertThat(first.answers().choice("dept").choice()).isEqualTo("support");
      assertThat(first.answers().choice("dept").probabilities().get("billing"))
          .isCloseTo(0.1, org.assertj.core.data.Offset.offset(1e-9));
      assertThat(first.answers().score("severity").score()).isEqualTo(2.0);
      assertThat(first.model()).isEqualTo("jev-1.13.0");
      assertThat(first.usage().inputTokens()).isEqualTo(10);
      assertThat(first.requestId()).contains("r1");

      assertThatThrownBy(() -> c.systemOne(REQUEST)).isInstanceOf(JevRateLimitException.class);
      assertThatThrownBy(() -> c.systemOneAsync(REQUEST).get())
          .isInstanceOf(ExecutionException.class);
      assertThat(c.systemOne(REQUEST).answers().noul("refund").noul()).isEqualTo(0.01);
      assertThat(c.systemOne(REQUEST).answers().noul("refund").noul())
          .as("default responder")
          .isEqualTo(0.5);
      assertThat(c.pendingScript()).isZero();

      assertThat(c.calls()).hasSize(5);
      assertThat(c.calls().get(0).options().headers()).containsEntry("x-test", "1");
      assertThat(c.calls().get(0).at()).isEqualTo(Instant.parse("2026-09-21T12:00:00Z"));
      assertThat(c.requests()).allMatch(r -> r.questions().equals(QUESTIONS));
      assertThat(c.lastCall()).isPresent();
      assertThatThrownBy(() -> c.calls().add(null))
          .isInstanceOf(UnsupportedOperationException.class);
      c.reset();
      assertThat(c.calls()).isEmpty();
      assertThat(c.lastCall()).isEmpty();
    }
  }

  @Test
  void replacementDefaultResponderAndModels() throws Exception {
    try (RecordingJevClient c = new RecordingJevClient()) {
      c.respondWith(
          req ->
              ScriptedAnswers.empty()
                  .put("refund", new net.codefinch.jev.NoulAnswer(0.99))
                  .build());
      assertThat(c.systemOne(REQUEST).answers().noul("refund").noul()).isEqualTo(0.99);
      assertThat(c.systemOne(REQUEST).answers().get("dept")).isEmpty();
      assertThat(c.models().models()).extracting(ModelMetadata::name).containsExactly("jev-latest");
      c.modelsResponse(
          new ModelList(
              List.of(new ModelMetadata("x", "d", "2026-02-02")),
              ResponseMetadata.of(Map.of(), "")));
      assertThat(c.modelsAsync().get().models())
          .extracting(ModelMetadata::name)
          .containsExactly("x");
      assertThat(c.calls())
          .extracting(RecordedCall::operation)
          .containsExactly("systemone", "systemone", "models", "models");
      assertThatThrownBy(() -> c.calls().get(2).systemOneRequest())
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void closeFollowsTheInterfaceContract() throws Exception {
    RecordingJevClient c = new RecordingJevClient();
    JevClient asInterface = c;
    asInterface.close();
    assertThat(c.isClosed()).isTrue();
    assertThatThrownBy(() -> asInterface.systemOne(REQUEST))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asInterface.models()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asInterface.modelsAsync().get())
        .hasCauseInstanceOf(IllegalStateException.class);
    asInterface.close(); // idempotent
  }

  @Test
  void scriptedAnswersValidation() {
    ChoiceQuestion dept = (ChoiceQuestion) QUESTIONS.asMap().get("dept");
    assertThatThrownBy(() -> ScriptedAnswers.empty().choice("dept", dept, "nope", 0.5, 0.5))
        .hasMessageContaining("not an option");
    assertThatThrownBy(
            () ->
                ScriptedAnswers.neutralAnswer(
                    ChoiceQuestion.of("?", ChoiceCriteria.builder().build())))
        .hasMessageContaining("no options");
    ScriptedAnswers s = ScriptedAnswers.neutral(QUESTIONS).without("dept");
    assertThat(s.get("dept")).isEmpty();
    assertThat(s.get("refund")).isPresent();
    assertThat(s.answers().ids()).containsExactly("refund", "severity");
    Map<String, Object> single = new HashMap<>();
    single.put("only", null);
    ChoiceQuestion one = ChoiceQuestion.of("?", ChoiceCriteria.of(single));
    assertThat(
            ScriptedAnswers.empty()
                .choice("c", one, "only", 1.0, 1.0)
                .answers()
                .choice("c")
                .probabilities())
        .containsEntry("only", 1.0);
    assertThat(ScriptedAnswers.neutralAnswer(NoulQuestion.of("?")))
        .isInstanceOf(net.codefinch.jev.NoulAnswer.class);
  }
}
