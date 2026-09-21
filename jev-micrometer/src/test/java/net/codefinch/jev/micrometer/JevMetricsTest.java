package net.codefinch.jev.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import net.codefinch.jev.Answer;
import net.codefinch.jev.Answers;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.Content;
import net.codefinch.jev.JevInternalServerException;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.Usage;
import org.junit.jupiter.api.Test;

class JevMetricsTest {

  private static SystemOneResponse response(Map<String, ? extends Answer> answers) {
    return new SystemOneResponse(
        "jev-1.13.0", Answers.of(answers), new Usage(210, 31), ResponseMetadata.of(Map.of(), "{}"));
  }

  private static CallObserver.Call success(SystemOneResponse r) {
    return new CallObserver.Call(
        "systemone",
        CallObserver.Outcome.SUCCESS,
        1,
        Duration.ofMillis(120),
        OptionalInt.of(200),
        Optional.of(r.model()),
        Optional.of(r),
        Optional.empty());
  }

  @Test
  void callAndAttemptTimersUseBoundedTags() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    JevMetrics metrics = JevMetrics.of(registry);
    metrics.onAttempt(
        new CallObserver.Attempt(
            "systemone",
            1,
            Duration.ofMillis(50),
            OptionalInt.of(500),
            Optional.of(new JevInternalServerException(500, Map.of(), "", null))));
    metrics.onAttempt(
        new CallObserver.Attempt(
            "systemone", 2, Duration.ofMillis(70), OptionalInt.of(200), Optional.empty()));
    metrics.onCall(success(response(Map.of("q", new NoulAnswer(0.9)))));
    metrics.onCall(
        new CallObserver.Call(
            "models",
            CallObserver.Outcome.ERROR,
            3,
            Duration.ofSeconds(1),
            OptionalInt.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(new RuntimeException())));

    assertThat(
            registry
                .get("jev.attempt")
                .tags("operation", "systemone", "status", "500", "attempt", "1", "outcome", "error")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("jev.attempt")
                .tags("status", "200", "attempt", "2", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("jev.call")
                .tags(
                    "operation",
                    "systemone",
                    "outcome",
                    "success",
                    "status",
                    "200",
                    "model",
                    "jev-1.13.0")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("jev.call")
                .tags(
                    "operation", "models", "outcome", "error", "status", "n/a", "model", "unknown")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("jev.tokens")
                .tags("type", "input", "model", "jev-1.13.0")
                .counter()
                .count())
        .isEqualTo(210);
    assertThat(
            registry
                .get("jev.tokens")
                .tags("type", "output", "model", "jev-1.13.0")
                .counter()
                .count())
        .isEqualTo(31);
    assertThat(registry.find("jev.confidence").meters())
        .as("no question tags by default")
        .isEmpty();
    assertThat(registry.find("jev.noul").meters()).isEmpty();
  }

  @Test
  void questionTagsAreRecordedOnlyForAllowlistedIds() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    JevMetrics metrics =
        JevMetrics.builder(registry).questionTags(Set.of("dept", "sev", "spam")).build();
    Map<String, Answer> answers = new LinkedHashMap<>();
    answers.put("dept", new ChoiceAnswer("a", Map.of("a", 1.0), 0.6));
    answers.put(
        "sev",
        new ScoreAnswer(
            1.0, Map.of(0, Content.of("l"), 1, Content.of("h")), Map.of(0, 0.5, 1, 0.5), 0.54));
    answers.put("spam", new NoulAnswer(0.93));
    answers.put("secret_id", new NoulAnswer(0.1));
    metrics.onCall(success(response(answers)));
    assertThat(
            registry
                .get("jev.confidence")
                .tags("question", "dept", "type", "choice")
                .summary()
                .mean())
        .isEqualTo(0.6);
    assertThat(
            registry
                .get("jev.confidence")
                .tags("question", "sev", "type", "score")
                .summary()
                .mean())
        .isEqualTo(0.54);
    assertThat(registry.get("jev.noul").tags("question", "spam", "type", "noul").summary().mean())
        .isEqualTo(0.93);
    assertThat(registry.find("jev.noul").tag("question", "secret_id").meters()).isEmpty();
    assertThat(metrics.questionTags()).containsExactlyInAnyOrder("dept", "sev", "spam");
    assertThatThrownBy(() -> metrics.questionTags().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * Plan §6 Phase 3 gate: 1 000 distinct question ids must not create new series unless
   * allowlisted.
   */
  @Test
  void thousandDistinctQuestionIdsCreateNoSeriesWithoutAllowlist() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    JevMetrics metrics = JevMetrics.builder(registry).questionTags(Set.of("allowed")).build();
    for (int i = 0; i < 1000; i++) {
      Map<String, Answer> answers = new LinkedHashMap<>();
      answers.put("question_" + i, new NoulAnswer(0.5));
      answers.put("allowed", new NoulAnswer(0.5));
      metrics.onCall(success(response(answers)));
    }
    List<Meter> all = registry.getMeters();
    long questionSeries = all.stream().filter(m -> m.getId().getTag("question") != null).count();
    assertThat(questionSeries).as("only the allowlisted id has a series").isEqualTo(1);
    assertThat(all).as("call timer + 2 token counters + 1 noul summary").hasSize(4);
    assertThat(registry.get("jev.noul").tag("question", "allowed").summary().count())
        .isEqualTo(1000);
  }

  @Test
  void prefixAndCommonTagsAndValidation() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    JevMetrics metrics =
        JevMetrics.builder(registry)
            .prefix("triage")
            .commonTags(List.of(Tag.of("service", "tickets")))
            .build();
    metrics.onCall(success(response(Map.of("q", new NoulAnswer(0.5)))));
    assertThat(registry.get("triage.call").tag("service", "tickets").timer().count()).isEqualTo(1);
    assertThat(registry.get("triage.tokens").tag("service", "tickets").counters()).hasSize(2);
    assertThatThrownBy(() -> JevMetrics.builder(registry).prefix(" "))
        .hasMessageContaining("blank");
    assertThatThrownBy(() -> JevMetrics.builder(null)).isInstanceOf(NullPointerException.class);
  }
}
