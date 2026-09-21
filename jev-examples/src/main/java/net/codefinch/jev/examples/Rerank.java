package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.State;
import net.codefinch.jev.patterns.FanOut;
import net.codefinch.jev.patterns.ItemAnswer;
import net.codefinch.jev.patterns.NoulThreshold;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Re-ranking (docs: cookbooks/rerank_typesafe): fast search produces a shortlist; one calibrated
 * judgment per candidate re-orders it. The cookbook asks one question per call; {@link FanOut} asks
 * the same question about every candidate in a single call, each candidate bound into its own
 * instructions, while the query stays in the shared state.
 */
public final class Rerank {

  static final String TASK =
      "The query is a customer question. Does this candidate passage answer it directly?";

  static final NoulCriteria CRITERIA =
      NoulCriteria.of(
          "The passage states the specific fact or instruction the query asks for",
          "The passage is only on a related topic, or answers a different question");

  /** Candidates below this are dropped even if they rank first. */
  static final NoulThreshold RELEVANT = NoulThreshold.at(0.5);

  private Rerank() {}

  record Ranked(String passage, double probability) {}

  /** The shortlist re-ordered by the model's probability that each passage answers the query. */
  static List<Ranked> rerank(JevClient client, String query, List<String> shortlist) {
    FanOut<String> fan = FanOut.noul(shortlist, FanOut.text(), TASK, CRITERIA);
    List<ItemAnswer<String>> answers =
        fan.answers(client.systemOne(State.of(Map.of("query", query)), fan.questions()));
    return answers.stream()
        .map(a -> new Ranked(a.item(), a.noul().noul()))
        .filter(r -> RELEVANT.decide(r.probability()) == NoulThreshold.Decision.YES)
        .sorted(Comparator.comparingDouble(Ranked::probability).reversed())
        .toList();
  }

  static final String SAMPLE_QUERY = "Can I return the jacket if I've worn it once?";

  /** A fast-search shortlist: lexically similar, only one actually answers. */
  static final List<String> SAMPLE_SHORTLIST =
      List.of(
          "Returns accepted within 30 days if unworn, with tags attached.",
          "The jacket is available in four colours and sizes XS to XXL.",
          "Warranty: two years against defects in materials and workmanship.",
          "Exchanges for a different size follow the same 30-day, unworn rule as returns.",
          "Machine wash cold on a gentle cycle; do not use fabric softener.");

  static void run(JevClient client, PrintStream out) {
    Examples.heading(out, "Re-ranking a shortlist with FanOut", "cookbooks/rerank_typesafe");
    out.println("query: " + SAMPLE_QUERY);
    List<Ranked> ranked = rerank(client, SAMPLE_QUERY, SAMPLE_SHORTLIST);
    if (ranked.isEmpty()) {
      out.println("  (no passage answers the query)");
    }
    for (Ranked r : ranked) {
      out.printf("  %.2f  %s%n", r.probability(), r.passage());
    }
  }

  static RecordingJevClient scripted() {
    double[] probabilities = {0.93, 0.03, 0.12, 0.71, 0.02};
    return new RecordingJevClient()
        .enqueue(
            req -> {
              ScriptedAnswers s = ScriptedAnswers.neutral(req.questions());
              for (int i = 0; i < probabilities.length; i++) {
                s.noul(FanOut.id(i), probabilities[i]);
              }
              return s.build();
            });
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
