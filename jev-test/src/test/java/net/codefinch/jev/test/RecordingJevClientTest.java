package net.codefinch.jev.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.codefinch.jev.Answer;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevRateLimitException;
import net.codefinch.jev.ModelList;
import net.codefinch.jev.ModelMetadata;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Questions;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.internal.Json;
import net.codefinch.jev.internal.ResponseParser;
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
      assertThatThrownBy(() -> c.systemOneAsync(REQUEST).get(2, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(JevRateLimitException.class);
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
      c.respondWith(req -> ScriptedAnswers.empty().put("refund", new NoulAnswer(0.99)).build());
      assertThat(c.systemOne(REQUEST).answers().noul("refund").noul()).isEqualTo(0.99);
      assertThat(c.systemOne(REQUEST).answers().get("dept")).isEmpty();
      assertThat(c.models().models()).extracting(ModelMetadata::name).containsExactly("jev-latest");
      c.modelsResponse(List.of(new ModelMetadata("x", "d", "2026-02-02")));
      assertThat(c.modelsAsync().get(2, TimeUnit.SECONDS).models())
          .extracting(ModelMetadata::name)
          .containsExactly("x");
      c.modelsResponse(
          new ModelList(
              List.of(new ModelMetadata("y", "d", "2026-02-02")),
              ResponseMetadata.of(Map.of(), "{}")));
      assertThat(c.models().models()).extracting(ModelMetadata::name).containsExactly("y");
      assertThat(c.calls())
          .extracting(RecordedCall::operation)
          .containsExactly("systemone", "systemone", "models", "models", "models");
      assertThatThrownBy(() -> c.calls().get(2).systemOneRequest())
          .isInstanceOf(IllegalStateException.class);
    }
  }

  // ---- Phase 3 review P2: the fake keeps the interface's lifecycle contract -------------------

  @Test
  void closedClientRejectsSyncAndAsyncCallsImmediatelyLikeTheHttpClient() {
    RecordingJevClient c = new RecordingJevClient();
    JevClient asInterface = c;
    asInterface.close();
    assertThat(c.isClosed()).isTrue();
    assertThatThrownBy(() -> asInterface.systemOne(REQUEST))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asInterface.systemOneAsync(REQUEST))
        .as("thrown, not a failed future")
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asInterface.models()).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> asInterface.modelsAsync()).isInstanceOf(IllegalStateException.class);
    assertThat(c.calls()).as("rejected calls are not recorded").isEmpty();
    asInterface.close(); // idempotent
  }

  @Test
  void asyncResponderRunsOffTheCallingThreadAndCloseCancelsIt() throws Exception {
    CountDownLatch inResponder = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean responderFinished = new AtomicBoolean();
    RecordingJevClient c = new RecordingJevClient();
    c.respondWith(
        req -> {
          inResponder.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          responderFinished.set(true);
          return ScriptedAnswers.neutralResponse(req.questions());
        });
    CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
    assertThat(f.isDone()).as("returned before the responder finished").isFalse();
    assertThat(inResponder.await(2, TimeUnit.SECONDS)).isTrue();
    c.close();
    assertThat(f.isDone()).as("close() completed the outstanding future").isTrue();
    assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
    release.countDown();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!responderFinished.get() && System.nanoTime() < end) {
      Thread.sleep(5);
    }
    assertThat(responderFinished.get()).isTrue();
    assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
        .as("late result discarded")
        .isInstanceOf(CancellationException.class);
  }

  @Test
  void userCancellationStopsPublicationAndSameThreadExecutorIsSynchronous() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    try (RecordingJevClient c = new RecordingJevClient()) {
      c.respondWith(
          req -> {
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return ScriptedAnswers.neutralResponse(req.questions());
          });
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      f.cancel(true);
      release.countDown();
      Thread.sleep(50);
      assertThat(f.isCancelled()).isTrue();
    }
    try (RecordingJevClient sync = new RecordingJevClient(Clock.systemUTC(), Runnable::run)) {
      CompletableFuture<SystemOneResponse> f = sync.systemOneAsync(REQUEST);
      assertThat(f.isDone()).as("same-thread executor completes before returning").isTrue();
      assertThat(f.get().answers().noul("refund").noul()).isEqualTo(0.5);
    }
  }

  /** Phase 3 review P2: lastCall() must read one snapshot even while reset() races it. */
  @Test
  void lastCallNeverThrowsWhileResetRaces() throws Exception {
    try (RecordingJevClient c = new RecordingJevClient()) {
      AtomicBoolean stop = new AtomicBoolean();
      AtomicBoolean failed = new AtomicBoolean();
      Thread writer =
          new Thread(
              () -> {
                while (!stop.get()) {
                  c.models();
                  c.reset();
                }
              });
      Thread reader =
          new Thread(
              () -> {
                while (!stop.get()) {
                  try {
                    c.lastCall();
                  } catch (RuntimeException e) {
                    failed.set(true);
                  }
                }
              });
      writer.start();
      reader.start();
      Thread.sleep(300);
      stop.set(true);
      writer.join(2000);
      reader.join(2000);
      assertThat(failed.get()).as("lastCall threw during a reset race").isFalse();
    }
  }

  /** Phase 3 review P2: scripted raw bodies are real wire JSON that the core parser accepts. */
  @Test
  void scriptedRawBodiesRoundTripThroughTheCoreParser() throws Exception {
    Questions q =
        Questions.builder()
            .noul("réfund \"quoted\"", "Money back?")
            .choice(
                "dept",
                "Which team?",
                ChoiceCriteria.builder().option("bil\"ling").option("支持").build())
            .score("sev", "How bad?", List.of("low", "high"))
            .build();
    ChoiceQuestion dept = (ChoiceQuestion) q.asMap().get("dept");
    ScoreQuestion sev = (ScoreQuestion) q.asMap().get("sev");
    SystemOneResponse built =
        ScriptedAnswers.neutral(q)
            .noul("réfund \"quoted\"", 0.93)
            .choice("dept", dept, "支持", 0.84, 0.6)
            .score("sev", sev, 1.3, 0.54)
            .model("jev-1.13.0 \"beta\"")
            .usage(210, 31)
            .headers(Map.of("x-typesafe-request-id", List.of("req-1")))
            .build();
    JsonNode tree = Json.parse(built.rawBody());
    assertThat(tree.get("answers").get("dept").get("type").textValue()).isEqualTo("choice");
    assertThat(tree.get("usage").get("input_tokens").longValue()).isEqualTo(210);
    SystemOneResponse parsed =
        ResponseParser.parseSystemOne(200, built.headers(), built.rawBody(), "test");
    assertThat(parsed.model()).isEqualTo(built.model());
    assertThat(parsed.usage()).isEqualTo(built.usage());
    assertThat(parsed.answers()).isEqualTo(built.answers());
    assertThat(parsed.requestId()).contains("req-1");
    Map<String, Answer> answers = parsed.answers().asMap();
    assertThat(answers.get("sev")).isInstanceOf(net.codefinch.jev.ScoreAnswer.class);
    assertThat(((net.codefinch.jev.ScoreAnswer) answers.get("sev")).legend().get(1))
        .isEqualTo(Content.of("high"));

    ModelList models = new RecordingJevClient().models();
    ModelList parsedModels =
        ResponseParser.parseModels(200, Map.of(), models.metadata().rawBody(), "test");
    assertThat(parsedModels.models())
        .as("default models body matches its typed list")
        .isEqualTo(models.models());
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
    assertThat(ScriptedAnswers.neutralAnswer(NoulQuestion.of("?"))).isInstanceOf(NoulAnswer.class);
  }
}
