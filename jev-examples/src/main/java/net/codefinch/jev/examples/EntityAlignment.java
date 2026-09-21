package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.Answers;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.Questions;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.State;
import net.codefinch.jev.patterns.ConfidenceGate;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Knowledge-graph entity alignment (docs: cookbooks/entity_alignment): for each candidate pair, one
 * Score says how the two descriptions relate and three Nouls explain why. The Score's level chooses
 * the outcome — leave unlinked, send to a curator, or assert {@code sameAs} — and its confidence
 * gates the automatic outcomes. Comparing the two alcohol percentages is arithmetic, so no question
 * asks about it.
 */
public final class EntityAlignment {

  static final List<String> LEVELS =
      List.of(
          "They describe two different products.",
          "They describe closely related products that may or may not be the same one: a variant,"
              + " a special edition, or a name that could plausibly refer to either.",
          "They describe one and the same product.");

  static final Questions QUESTIONS =
      Questions.builder()
          .score("link_state", "How do the two entity descriptions relate as products?", LEVELS)
          .noul("same_name", "Do the two entities state the same beer name?")
          .noul("same_brewery", "Are the two entities from the same brewery?")
          .noul("same_style", "Do the two entities describe the same beer style?")
          .build();

  /** Two alcohol percentages this close count as equal (arithmetic, not a question). */
  static final double ABV_TOLERANCE = 0.05;

  /**
   * Only a confident score (ACT, at or above 0.7) produces an automatic outcome. CONFIRM routes to
   * the curator with the suggested outcome attached; ESCALATE routes there with no suggestion. The
   * cookbook itself has no gate; this one is the example's own policy.
   */
  static final ConfidenceGate LINK = ConfidenceGate.of(0.5, 0.7);

  private EntityAlignment() {}

  record Entity(String name, String brewery, String style, double abv) {}

  record Pair(String id, Entity a, Entity b) {}

  record Outcome(String pairId, String decision, String evidence) {}

  static Outcome align(JevClient client, Pair pair) {
    Answers answers =
        client
            .systemOne(
                State.of(Map.of("entity_a", describe(pair.a()), "entity_b", describe(pair.b()))),
                QUESTIONS)
            .answers();
    ScoreAnswer link = answers.score("link_state");
    String decision =
        switch (LINK.decide(link)) {
          case ACT -> outcome((int) Math.round(link.score()));
          case CONFIRM ->
              "curator queue (confirm: " + outcome((int) Math.round(link.score())) + ")";
          case ESCALATE -> "curator queue (uncertain)";
        };
    String evidence =
        String.format(
            "name %.2f, brewery %.2f, style %.2f, abv %s",
            answers.noul("same_name").noul(),
            answers.noul("same_brewery").noul(),
            answers.noul("same_style").noul(),
            Math.abs(pair.a().abv() - pair.b().abv()) < ABV_TOLERANCE ? "equal" : "differs");
    return new Outcome(pair.id(), decision, evidence);
  }

  static String outcome(int level) {
    return switch (level) {
      case 0 -> "leave unlinked";
      case 1 -> "curator queue";
      default -> "assert sameAs";
    };
  }

  static Map<String, Object> describe(Entity e) {
    return Map.of("name", e.name(), "brewery", e.brewery(), "style", e.style(), "abv", e.abv());
  }

  static final List<Pair> SAMPLE_PAIRS =
      List.of(
          new Pair(
              "p1",
              new Entity("Riverbend Pale Ale", "Riverbend Brewing Co.", "American Pale Ale", 5.4),
              new Entity("Riverbend Pale", "Riverbend Brewing", "Pale Ale - American", 5.4)),
          new Pair(
              "p2",
              new Entity("Riverbend Pale Ale", "Riverbend Brewing Co.", "American Pale Ale", 5.4),
              new Entity(
                  "Riverbend Pale Ale — Citra Edition",
                  "Riverbend Brewing Co.",
                  "American Pale Ale",
                  5.8)),
          new Pair(
              "p3",
              new Entity("Riverbend Pale Ale", "Riverbend Brewing Co.", "American Pale Ale", 5.4),
              new Entity("Northgate Porter", "Northgate Ales", "Porter", 6.1)));

  static void run(JevClient client, PrintStream out) {
    Examples.heading(out, "Entity alignment", "cookbooks/entity_alignment");
    for (Pair pair : SAMPLE_PAIRS) {
      Outcome o = align(client, pair);
      out.printf("%-4s %-28s %s%n", o.pairId(), o.decision(), o.evidence());
    }
  }

  static RecordingJevClient scripted() {
    ScoreQuestion link = (ScoreQuestion) QUESTIONS.asMap().get("link_state");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("link_state", link, 1.9, 0.85)
                .noul("same_name", 0.9)
                .noul("same_brewery", 0.95)
                .noul("same_style", 0.9))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("link_state", link, 1.1, 0.75)
                .noul("same_name", 0.4)
                .noul("same_brewery", 0.97)
                .noul("same_style", 0.9))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("link_state", link, 0.05, 0.97)
                .noul("same_name", 0.01)
                .noul("same_brewery", 0.02)
                .noul("same_style", 0.03));
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
