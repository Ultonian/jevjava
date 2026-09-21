package net.codefinch.jev.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.JevApiException;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevRateLimitException;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.Questions;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.RetryPolicy;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;
import org.junit.jupiter.api.Test;

/**
 * The README's Usage snippets, compiled and run against the fake (plan §7: samples compile in CI).
 */
class ReadmeUsageTest {

  private static final String TICKET = "I was charged twice for order 4411, please refund one.";

  // --- "Create a client" (the builder half; fromEnv() needs a key) -----------------------------
  @Test
  void createClient() {
    String apiKey = "test-key";
    JevClient configured =
        JevClient.builder()
            .apiKey(apiKey)
            .defaultModel("jev-latest")
            .timeout(Duration.ofSeconds(10))
            .deadline(Duration.ofSeconds(30))
            .retryPolicy(RetryPolicy.DEFAULT.withMaxRetries(3))
            .build();
    configured.close();
    assertThat(configured).isNotNull();
  }

  // --- "Ask" and "Read the answers"
  // ---------------------------------------------------------------
  @Test
  void askAndReadTheAnswers() {
    String ticketText = TICKET;
    State state = State.of(Map.of("ticket", Map.of("text", ticketText, "customer_tier", "gold")));

    Questions questions =
        Questions.builder()
            .noul(
                "refund_requested",
                "Does `ticket.text` ask for money back?",
                NoulCriteria.of(
                    "Wants a refund, chargeback or credit", "No request for money back"))
            .choice(
                "department",
                "Which team should handle `ticket.text`?",
                ChoiceCriteria.builder()
                    .option("billing", "Invoices, charges, refunds")
                    .option("shipping", "Delivery, tracking, damaged parcels")
                    .option("other")
                    .build())
            .score(
                "severity",
                "How severe is the problem for the customer?",
                List.of("Cosmetic", "Inconvenient, workaround exists", "Blocking"))
            .build();

    try (RecordingJevClient client = scripted(questions)) {
      SystemOneResponse response = client.systemOne(state, questions);

      Answers answers = response.answers();
      double refund = answers.noul("refund_requested").noul();
      ChoiceAnswer dept = answers.choice("department");
      ScoreAnswer severity = answers.score("severity");

      String summary =
          switch (answers.get("department").orElseThrow()) {
            case NoulAnswer n -> "yes with p=" + n.noul();
            case ChoiceAnswer c -> c.choice() + " (confidence " + c.confidence() + ")";
            case ScoreAnswer sc -> "level " + sc.score() + " of " + sc.topLevel();
          };

      assertThat(refund).isEqualTo(0.95);
      assertThat(dept.choice()).isEqualTo("billing");
      assertThat(severity.topLevel()).isEqualTo(2);
      assertThat(summary).isEqualTo("billing (confidence 0.9)");
      assertThat(response.model()).isEqualTo("jev-1.13.0");
      assertThat(response.usage().inputTokens()).isEqualTo(210);
      assertThat(response.requestId()).contains("req-readme");
      assertThatThrownBy(() -> answers.noul("nope"))
          .isInstanceOf(net.codefinch.jev.JevMissingAnswerException.class);
      assertThat(answers.get("nope")).isEmpty();
    }
  }

  // --- "Per-call options and models"
  // ---------------------------------------------------------------
  @Test
  void perCallOptionsAndModels() {
    Questions questions = Questions.of("q", net.codefinch.jev.NoulQuestion.of("?"));
    State state = State.of("s");
    try (RecordingJevClient client = new RecordingJevClient()) {
      SystemOneRequest request = SystemOneRequest.of(state, questions).withModel("jev-preview");
      RequestOptions options =
          RequestOptions.builder()
              .timeout(Duration.ofSeconds(5))
              .retry(p -> p.withMaxRetries(0))
              .header("X-Request-Source", "batch-job")
              .build();
      SystemOneResponse r = client.systemOne(request, options);
      assertThat(r).isNotNull();
      assertThat(client.lastCall().orElseThrow().systemOneRequest().model())
          .contains("jev-preview");
      assertThat(client.lastCall().orElseThrow().options().headers())
          .containsEntry("x-request-source", "batch-job");
      assertThat(options.resolveRetry(RetryPolicy.DEFAULT).maxRetries()).isZero();
    }
  }

  // --- "Async"
  // ------------------------------------------------------------------------------------
  @Test
  void async() throws Exception {
    Questions questions = Questions.of("q", net.codefinch.jev.NoulQuestion.of("?"));
    State state = State.of("s");
    try (RecordingJevClient client = new RecordingJevClient()) {
      CompletableFuture<SystemOneResponse> future = client.systemOneAsync(state, questions);
      CompletableFuture<Void> routed = future.thenAccept(r -> route(r.answers()));
      routed.get(2, TimeUnit.SECONDS);
      CompletableFuture<SystemOneResponse> another = client.systemOneAsync(state, questions);
      another.cancel(true);
      assertThat(another.isCancelled()).isTrue();
    }
  }

  // --- "Errors and retries"
  // -------------------------------------------------------------------------
  @Test
  void errorsAndRetries() {
    Questions questions = Questions.of("q", net.codefinch.jev.NoulQuestion.of("?"));
    State state = State.of("s");
    StringBuilder log = new StringBuilder();
    try (RecordingJevClient client = new RecordingJevClient()) {
      client
          .enqueueFailure(
              new JevRateLimitException(Map.of("retry-after-ms", List.of("1500")), "", null))
          .enqueueFailure(
              JevApiException.fromStatus(
                  500,
                  Map.of("x-typesafe-request-id", List.of("req-9")),
                  "{\"message\":\"boom\"}",
                  null));
      for (int i = 0; i < 2; i++) {
        try {
          client.systemOne(state, questions);
        } catch (JevRateLimitException e) {
          e.retryAfter()
              .ifPresent(delay -> log.append("rate limited; server suggests ").append(delay));
        } catch (JevApiException e) {
          log.append(e.status())
              .append(' ')
              .append(e.getMessage())
              .append(" request_id=")
              .append(e.requestId().orElse("-"));
        }
      }
    }
    assertThat(log.toString())
        .contains("rate limited; server suggests PT1.5S", "500 500 boom", "request_id=req-9");
    assertThat(RetryPolicy.DEFAULT.maxRetries()).isEqualTo(2);
    assertThat(RetryPolicy.DEFAULT.httpStatuses()).contains(408, 429, 500, 529);
    assertThat(RetryPolicy.DEFAULT.maxRetryAfter()).isEqualTo(Duration.ofSeconds(60));
  }

  private static void route(Answers answers) {
    assertThat(answers.size()).isEqualTo(1);
  }

  private static RecordingJevClient scripted(Questions questions) {
    ChoiceQuestion dept = (ChoiceQuestion) questions.asMap().get("department");
    net.codefinch.jev.ScoreQuestion sev =
        (net.codefinch.jev.ScoreQuestion) questions.asMap().get("severity");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(questions)
                .noul("refund_requested", 0.95)
                .choice("department", dept, "billing", 0.85, 0.9)
                .score("severity", sev, 1.4, 0.7)
                .model("jev-1.13.0")
                .usage(210, 31)
                .headers(Map.of("x-typesafe-request-id", List.of("req-readme"))));
  }
}
