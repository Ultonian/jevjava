package net.codefinch.jev.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import net.codefinch.jev.test.RecordingJevClient;
import org.junit.jupiter.api.Test;

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

  @Test
  void thresholdsAreExplicitInTheExample() {
    assertThat(TicketTriage.REFUND.no()).isEqualTo(0.2);
    assertThat(TicketTriage.REFUND.yes()).isEqualTo(0.8);
    assertThat(TicketTriage.ROUTING.low()).isEqualTo(0.5);
    assertThat(TicketTriage.ROUTING.high()).isEqualTo(0.85);
    assertThat(TicketTriage.PRIORITY.weights()).containsOnlyKeys("severity");
  }

  @Test
  void mainFallsBackToTheFakeWithoutKey() {
    if (System.getenv("TYPESAFE_API_KEY") == null) {
      TicketTriage.main(new String[0]);
    }
  }
}
