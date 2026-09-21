package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.Questions;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.patterns.Composite;
import net.codefinch.jev.patterns.ConfidenceGate;
import net.codefinch.jev.patterns.NoulThreshold;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Triage a support ticket in one call: three questions, then code routes on the answers.
 *
 * <p>Everything a reviewer needs to judge the behaviour — the questions and the thresholds — is in
 * this one file. Run against the real API with {@code TYPESAFE_API_KEY} set, or without a key
 * against {@link RecordingJevClient} with scripted answers:
 *
 * <pre>
 *   ./mvnw -q -DskipTests install &amp;&amp; ./mvnw -q -pl jev-examples exec:java
 *   jbang --deps net.codefinch.jev:jev-core:VERSION,net.codefinch.jev:jev-test:VERSION \\
 *       jev-examples/src/main/java/net/codefinch/jev/examples/TicketTriage.java   (once published)
 * </pre>
 */
public final class TicketTriage {

  // ---- The questions: one narrow judgment each, referencing the state by field name. ---------

  static final Questions QUESTIONS =
      Questions.builder()
          .noul(
              "refund_requested",
              "Does `ticket.text` ask for money back?",
              NoulCriteria.of(
                  "The customer wants a refund, chargeback or credit",
                  "No request for money back, even if they are unhappy"))
          .choice(
              "department",
              "Which team should handle `ticket.text`?",
              ChoiceCriteria.builder()
                  .option("billing", "Invoices, charges, refunds, payment methods")
                  .option("shipping", "Delivery, tracking, damaged or missing parcels")
                  .option("account", "Login, password, personal details")
                  .option("other")
                  .build())
          .score(
              "severity",
              "How severe is the problem in `ticket.text` for the customer?",
              List.of(
                  "Cosmetic; no impact",
                  "Inconvenient, but a workaround exists",
                  "Blocking; the customer cannot get what they paid for"))
          .build();

  // ---- The thresholds: application decisions, not SDK defaults. ------------------------------

  /** Refund handling: act on a clear yes, ignore a clear no, ask a person in between. */
  static final NoulThreshold REFUND = NoulThreshold.of(0.2, 0.8);

  /** Department routing: auto-route only when the model is sure; confirm in the middle. */
  static final ConfidenceGate ROUTING = ConfidenceGate.of(0.5, 0.85);

  /** Priority: severity dominates, refund requests add weight. */
  static final Composite PRIORITY = Composite.score(Map.of("severity", 1.0));

  private TicketTriage() {}

  /** One triage decision. */
  record Triage(String department, String routing, String refund, double priority) {}

  /** Runs the three questions and routes on the answers. */
  static Triage triage(JevClient client, String ticketText) {
    SystemOneResponse response =
        client.systemOne(State.of(Map.of("ticket", Map.of("text", ticketText))), QUESTIONS);
    Answers a = response.answers();

    String department = a.choice("department").choice();
    String routing =
        switch (ROUTING.decide(a.choice("department"))) {
          case ACT -> "auto-routed to " + department;
          case CONFIRM -> "suggested " + department + "; agent confirms";
          case ESCALATE -> "unsure; triage queue";
        };
    String refund =
        switch (REFUND.decide(a.noul("refund_requested"))) {
          case YES -> "refund workflow";
          case NO -> "no refund";
          case UNSURE -> "ask the customer";
        };
    double priority = PRIORITY.apply(a) + (a.noul("refund_requested").noul() > 0.8 ? 0.25 : 0);
    return new Triage(department, routing, refund, Math.min(1.0, priority));
  }

  /** Entry point: real API if {@code TYPESAFE_API_KEY} is set, otherwise the recording fake. */
  public static void main(String[] args) {
    String key = System.getenv("TYPESAFE_API_KEY");
    try (JevClient client = key == null || key.isBlank() ? scripted() : JevClient.fromEnv()) {
      run(client, System.out, key == null || key.isBlank() ? "recording fake" : "live API");
    }
  }

  static void run(JevClient client, PrintStream out, String source) {
    out.println("Source: " + source);
    for (String ticket : SAMPLE_TICKETS) {
      Triage t = triage(client, ticket);
      out.printf(
          "%-52s -> %-38s | %-16s | priority %.2f%n",
          quote(ticket), t.routing(), t.refund(), t.priority());
    }
  }

  static final List<String> SAMPLE_TICKETS =
      List.of(
          "I was charged twice for order 4411, please refund one of them.",
          "Parcel says delivered but nothing arrived. Where is it?",
          "The font on the invoice PDF looks slightly off.");

  /** A fake with answers shaped like the real ones, so the example runs without a key. */
  static RecordingJevClient scripted() {
    var dept = (net.codefinch.jev.ChoiceQuestion) QUESTIONS.asMap().get("department");
    var sev = (net.codefinch.jev.ScoreQuestion) QUESTIONS.asMap().get("severity");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .noul("refund_requested", 0.96)
                .choice("department", dept, "billing", 0.9, 0.88)
                .score("severity", sev, 1.9, 0.7))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .noul("refund_requested", 0.3)
                .choice("department", dept, "shipping", 0.7, 0.62)
                .score("severity", sev, 2.0, 0.9))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .noul("refund_requested", 0.02)
                .choice("department", dept, "other", 0.4, 0.3)
                .score("severity", sev, 0.1, 0.95));
  }

  private static String quote(String s) {
    return s.length() > 50 ? "\"" + s.substring(0, 47) + "…\"" : "\"" + s + "\"";
  }
}
