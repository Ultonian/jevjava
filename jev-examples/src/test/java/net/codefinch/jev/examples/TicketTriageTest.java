package net.codefinch.jev.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Plan §6 Phase 3 gate: the example runs against the recording fake in CI. */
class TicketTriageTest {

  @Test
  void runsEndToEndAgainstTheRecordingFake() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (RecordingJevClient client = TicketTriage.scripted()) {
      TicketTriage.run(client, new PrintStream(buffer, true, StandardCharsets.UTF_8), "test");
      assertThat(client.requests()).hasSize(3);
      assertThat(client.requests().get(0).questions().ids())
          .containsExactly("refund_requested", "department", "severity");
      assertThat(
              client
                  .requests()
                  .get(0)
                  .state()
                  .content()
                  .toJson()
                  .get("ticket")
                  .get("text")
                  .textValue())
          .contains("charged twice");
    }
    String out = buffer.toString(StandardCharsets.UTF_8);
    assertThat(out).contains("auto-routed to billing", "refund workflow");
    assertThat(out).contains("suggested shipping; agent confirms", "ask the customer");
    assertThat(out).contains("unsure; triage queue", "no refund");
  }

  @ParameterizedTest
  @CsvSource({
    "0.79, 0.49, 0.5, unsure",
    "0.8, 0.5, 0.75, suggested",
    "0.81, 0.85, 0.75, auto-routed"
  })
  void routingAndRefundPriorityUseTheDeclaredBoundaries(
      double refund, double confidence, double priority, String routing) {
    try (RecordingJevClient client =
        new RecordingJevClient()
            .enqueue(
                ScriptedAnswers.neutral(TicketTriage.QUESTIONS)
                    .noul("refund_requested", refund)
                    .choice("department", "billing", 0.9, confidence)
                    .score("severity", 1.0, 0.9))) {
      TicketTriage.Triage result = TicketTriage.triage(client, "ticket");
      assertThat(result.priority()).isEqualTo(priority);
      assertThat(result.routing()).startsWith(routing);
      assertThat(result.refund()).isEqualTo(refund >= 0.8 ? "refund workflow" : "ask the customer");
    }
  }

  @Test
  @DisabledIfEnvironmentVariable(named = "TYPESAFE_API_KEY", matches = "(?s).*")
  void mainFallsBackToTheFakeWithoutKey() {
    TicketTriage.main(new String[0]);
  }
}
