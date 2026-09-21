package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.List;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.Questions;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.State;
import net.codefinch.jev.patterns.ConfidenceGate;
import net.codefinch.jev.patterns.NoulThreshold;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Speculative fan-out (docs: patterns/fan-out): ask every question the workflow might need in one
 * call — including ones that only matter for some categories — and let code read the relevant
 * answers. Extra questions barely change latency; a second round trip would.
 */
public final class SupportTicketFanOut {

  static final Questions QUESTIONS =
      Questions.builder()
          .choice(
              "category",
              "Determine the broad category of this support ticket",
              ChoiceCriteria.builder()
                  .option(
                      "bug_report",
                      "The user is reporting something that is broken or producing errors")
                  .option("billing", "Charges, invoices, refunds, subscriptions")
                  .option("feature_request", "The user is requesting new functionality")
                  .option("account", "Login, permissions, profile, security")
                  .build())
          .score(
              "bug_severity",
              "How severe is the reported issue",
              List.of(
                  "Cosmetic; no impact to functionality",
                  "Broken or degraded feature; workaround exists",
                  "Blocking issue; no workaround exists"))
          .noul(
              "has_reproducible_steps", "The user describes specific steps to reproduce the issue")
          .noul("refund_requested", "The user is explicitly asking for a refund or credit")
          .score(
              "frustration",
              "How frustrated the user appears",
              List.of("Calm, matter-of-fact", "Frustrated but civil", "Very angry"))
          .build();

  /** Route on the category only when the model is reasonably sure; otherwise a person triages. */
  static final ConfidenceGate CATEGORY = ConfidenceGate.of(0.5, 0.8);

  static final NoulThreshold REPRODUCIBLE = NoulThreshold.at(0.5);
  static final NoulThreshold REFUND = NoulThreshold.of(0.2, 0.8);

  /** Frustration at or above "frustrated but civil" flags the ticket regardless of category. */
  static final double FRUSTRATION_FLAG = 1.0;

  private SupportTicketFanOut() {}

  record Routing(String category, String handler, boolean frustrated) {}

  static Routing route(JevClient client, String ticket) {
    Answers a = client.systemOne(State.of(ticket), QUESTIONS).answers();
    String category = a.choice("category").choice();
    String handler =
        switch (CATEGORY.decide(a.choice("category"))) {
          case ESCALATE -> "manual triage (category unclear)";
          case CONFIRM, ACT ->
              switch (category) {
                case "bug_report" -> {
                  double severity = a.score("bug_severity").score();
                  String repro =
                      REPRODUCIBLE.decide(a.noul("has_reproducible_steps"))
                              == NoulThreshold.Decision.YES
                          ? "with repro steps"
                          : "needs repro steps";
                  yield severity >= 1.5
                      ? "engineering on-call (blocking, " + repro + ")"
                      : "bug backlog (" + repro + ")";
                }
                case "billing" ->
                    switch (REFUND.decide(a.noul("refund_requested"))) {
                      case YES -> "billing: refund workflow";
                      case NO -> "billing: general";
                      case UNSURE -> "billing: agent checks for a refund request";
                    };
                case "feature_request" -> "product backlog";
                default -> "account support";
              };
        };
    boolean frustrated = a.score("frustration").score() >= FRUSTRATION_FLAG;
    return new Routing(category, handler, frustrated);
  }

  static final List<String> SAMPLE_TICKETS =
      List.of(
          "Hi, I placed an order (#98423) last Thursday and was charged twice. I also can't log in"
              + " after the site update, and adding Apple Pay would be really helpful. This is"
              + " getting frustrating.",
          "Export to CSV fails every time: click Reports > Export, choose CSV, and the page shows"
              + " 'error 500'. Chrome and Firefox, since yesterday.",
          "Would love a dark mode for the mobile app. Thanks for a great product!");

  static void run(JevClient client, PrintStream out) {
    Examples.heading(out, "Support ticket fan-out", "patterns/fan-out");
    for (String ticket : SAMPLE_TICKETS) {
      Routing r = route(client, ticket);
      out.printf(
          "%-44s -> %-16s %-52s%s%n",
          Examples.clip(ticket, 42),
          r.category(),
          r.handler(),
          r.frustrated() ? "  [frustrated]" : "");
    }
  }

  static RecordingJevClient scripted() {
    ChoiceQuestion category = (ChoiceQuestion) QUESTIONS.asMap().get("category");
    ScoreQuestion severity = (ScoreQuestion) QUESTIONS.asMap().get("bug_severity");
    ScoreQuestion frustration = (ScoreQuestion) QUESTIONS.asMap().get("frustration");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("category", category, "billing", 0.55, 0.55)
                .noul("refund_requested", 0.9)
                .score("frustration", frustration, 1.4, 0.7))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("category", category, "bug_report", 0.95, 0.93)
                .score("bug_severity", severity, 1.9, 0.8)
                .noul("has_reproducible_steps", 0.97)
                .score("frustration", frustration, 0.8, 0.8))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("category", category, "feature_request", 0.9, 0.9)
                .noul("refund_requested", 0.01)
                .score("frustration", frustration, 0.05, 0.95));
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
