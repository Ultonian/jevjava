package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.List;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.Questions;
import net.codefinch.jev.State;
import net.codefinch.jev.patterns.ConfidenceGate;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Confidence-gated routing (docs: patterns/confidence-routing): one intent question, but the
 * confidence needed to act depends on what the action would do. Showing a balance is cheap to get
 * wrong; approving a transfer is not, so it needs 0.85 to go through without confirmation.
 */
public final class VoiceBanking {

  static final Questions QUESTIONS =
      Questions.builder()
          .choice(
              "intent",
              "What action is the user requesting?",
              ChoiceCriteria.builder()
                  .option("check_balance", "Check the balance of an account")
                  .option("approve_transfer", "Approve the pending transfer request")
                  .option("other", "Something else")
                  .build())
          .build();

  /** Below 0.6 on any action, a support agent takes over (the docs' floor). */
  static final ConfidenceGate BALANCE = ConfidenceGate.of(0.6, 0.6);

  /** Transfers: 0.6 to 0.85 asks the user to confirm; 0.85 and above approves. */
  static final ConfidenceGate TRANSFER = ConfidenceGate.of(0.6, 0.85);

  private VoiceBanking() {}

  static String handle(JevClient client, String command) {
    ChoiceAnswer intent = client.systemOne(State.of(command), QUESTIONS).answers().choice("intent");
    return switch (intent.choice()) {
      case "check_balance" ->
          BALANCE.decide(intent) == ConfidenceGate.Decision.ESCALATE
              ? "support agent"
              : "show the balance";
      case "approve_transfer" ->
          switch (TRANSFER.decide(intent)) {
            case ACT -> "approve the transfer";
            case CONFIRM -> "ask the user to confirm the transfer";
            case ESCALATE -> "support agent";
          };
      default -> "support agent";
    };
  }

  static final List<String> SAMPLE_COMMANDS =
      List.of(
          "What's my current account balance?",
          "Yes, go ahead and approve the transfer to my savings.",
          "Uh, the thing from yesterday, do it.",
          "I want to dispute a charge on my statement.");

  static void run(JevClient client, PrintStream out) {
    Examples.heading(
        out, "Voice banking (confidence-gated routing)", "patterns/confidence-routing");
    for (String command : SAMPLE_COMMANDS) {
      out.printf("%-56s -> %s%n", Examples.clip(command, 54), handle(client, command));
    }
  }

  static RecordingJevClient scripted() {
    ChoiceQuestion intent = (ChoiceQuestion) QUESTIONS.asMap().get("intent");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "check_balance", 0.96, 0.95))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "approve_transfer", 0.93, 0.9))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "approve_transfer", 0.7, 0.72))
        .enqueue(ScriptedAnswers.neutral(QUESTIONS).choice("intent", intent, "other", 0.9, 0.88));
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
