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
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Intent routing (docs: patterns/intent-routing): classify intent and complexity in one call, then
 * send each message to the cheapest handler that can resolve it — a database lookup, an LLM with
 * domain context, or a person.
 */
public final class IntentRouting {

  static final Questions QUESTIONS =
      Questions.builder()
          .choice(
              "intent",
              "The primary intent of this customer message",
              ChoiceCriteria.builder()
                  .option("order_status", "Asking about an existing order")
                  .option("product_question", "Asking about a product before buying")
                  .option("return_exchange", "Wants to return or exchange something")
                  .option("complaint", "Unhappy with experience, wants resolution")
                  .build())
          .score(
              "complexity",
              "How complex is this request to resolve",
              List.of(
                  "Simple lookup or standard procedure",
                  "Requires some judgment or multi-step process",
                  "Unusual situation, edge case, or escalation needed"))
          .build();

  /** Intent confidence below 0.5 goes straight to a person (the docs' gate). */
  static final ConfidenceGate INTENT = ConfidenceGate.of(0.5, 0.5);

  /** Complexity at or above this level needs a person whatever the intent. */
  static final double ESCALATION_COMPLEXITY = 1.5;

  private IntentRouting() {}

  static String route(JevClient client, String message) {
    Answers a = client.systemOne(State.of(message), QUESTIONS).answers();
    if (INTENT.decide(a.choice("intent")) == ConfidenceGate.Decision.ESCALATE) {
      return "human agent (intent unclear)";
    }
    double complexity = a.score("complexity").score();
    if (complexity >= ESCALATION_COMPLEXITY) {
      return "human agent (complex)";
    }
    return switch (a.choice("intent").choice()) {
      case "order_status" -> "order database lookup";
      case "product_question" -> "LLM with product catalogue context";
      case "return_exchange" -> "returns workflow";
      default -> "human agent (complaint)";
    };
  }

  static final List<String> SAMPLE_MESSAGES =
      List.of(
          "Where is order 55120? It says shipped on Monday.",
          "Does the trail jacket pack down small enough for carry-on?",
          "I'd like to exchange the medium for a large, unworn with tags.",
          "Third time writing. Wrong item, then a damaged replacement, and nobody replies. Fix"
              + " this.");

  static void run(JevClient client, PrintStream out) {
    Examples.heading(out, "Intent routing", "patterns/intent-routing");
    for (String message : SAMPLE_MESSAGES) {
      out.printf("%-56s -> %s%n", Examples.clip(message, 54), route(client, message));
    }
  }

  static RecordingJevClient scripted() {
    ChoiceQuestion intent = (ChoiceQuestion) QUESTIONS.asMap().get("intent");
    ScoreQuestion complexity = (ScoreQuestion) QUESTIONS.asMap().get("complexity");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "order_status", 0.95, 0.94)
                .score("complexity", complexity, 0.1, 0.9))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "product_question", 0.9, 0.88)
                .score("complexity", complexity, 0.6, 0.7))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "return_exchange", 0.92, 0.9)
                .score("complexity", complexity, 0.4, 0.8))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .choice("intent", intent, "complaint", 0.85, 0.8)
                .score("complexity", complexity, 1.8, 0.75));
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
