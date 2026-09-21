package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.ChoiceCriteria;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.NoulCriteria;
import net.codefinch.jev.Questions;
import net.codefinch.jev.State;
import net.codefinch.jev.patterns.NoulThreshold;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Line-by-line search (docs: cookbooks/semantic_find): the document goes in the state with an id
 * per line; a Choice over those ids (no descriptions — the text is already in the state) points at
 * the answering line, and a Noul in the same request says whether any line answers at all, since
 * choice probabilities always sum to 1 even when nothing fits.
 */
public final class LineSearch {

  static final List<String> DOCUMENT =
      List.of(
          "Trail Jacket — care and use",
          "The shell is 20-denier ripstop nylon with a PFAS-free DWR finish.",
          "Machine wash cold on a gentle cycle; do not use fabric softener.",
          "Tumble dry low for 20 minutes to reactivate the water repellency.",
          "Packed size is 12 x 8 cm; it stows into its own chest pocket.",
          "Weight: 215 g in size medium.",
          "Reflective trim on the back hem and both cuffs.",
          "Warranty: two years against defects in materials and workmanship.",
          "Returns accepted within 30 days if unworn, with tags attached.");

  /** How sure the "exists" answer must be before we trust the pointed-at line. */
  static final NoulThreshold EXISTS = NoulThreshold.of(0.3, 0.7);

  private LineSearch() {}

  static String lineId(int i) {
    return "L" + (i + 1);
  }

  /** The document as state: one object per line, so the ids are visible to the model. */
  static State state() {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (int i = 0; i < DOCUMENT.size(); i++) {
      lines.add(Map.of("id", lineId(i), "text", DOCUMENT.get(i)));
    }
    return State.of(Map.of("lines", lines));
  }

  static Questions questions(String query) {
    ChoiceCriteria.Builder ids = ChoiceCriteria.builder();
    for (int i = 0; i < DOCUMENT.size(); i++) {
      ids.option(lineId(i)); // undescribed: the state already holds each line's text
    }
    return Questions.builder()
        .choice(
            "where",
            "Which line of the document contains the answer to: \"" + query + "\"?",
            ids.build())
        .noul(
            "exists",
            "Does any line of the document address or answer: \"" + query + "\"?",
            NoulCriteria.of(
                "At least one line of the document states or directly implies the answer",
                "No line of the document addresses this"))
        .build();
  }

  record Hit(String lineId, String text, double probability) {}

  /** The answering line, or empty when the document does not address the query. */
  static Optional<Hit> find(JevClient client, String query) {
    Answers a = client.systemOne(state(), questions(query)).answers();
    if (EXISTS.decide(a.noul("exists")) != NoulThreshold.Decision.YES) {
      return Optional.empty();
    }
    ChoiceAnswer where = a.choice("where");
    int index = Integer.parseInt(where.choice().substring(1)) - 1;
    return Optional.of(
        new Hit(where.choice(), DOCUMENT.get(index), where.probabilities().get(where.choice())));
  }

  static final List<String> SAMPLE_QUERIES =
      List.of("How do I wash it?", "How much does it weigh?", "Is it available in blue?");

  static void run(JevClient client, PrintStream out) {
    Examples.heading(out, "Line search", "cookbooks/semantic_find");
    for (String query : SAMPLE_QUERIES) {
      Optional<Hit> hit = find(client, query);
      out.printf(
          "%-30s -> %s%n",
          query,
          hit.map(
                  h ->
                      h.lineId()
                          + " (p="
                          + String.format("%.2f", h.probability())
                          + "): "
                          + h.text())
              .orElse("no answer in the document"));
    }
  }

  static RecordingJevClient scripted() {
    return new RecordingJevClient()
        .enqueue(req -> hit(req.questions(), "L3", 0.86, 0.95))
        .enqueue(req -> hit(req.questions(), "L6", 0.91, 0.97))
        .enqueue(req -> hit(req.questions(), "L2", 0.31, 0.08));
  }

  private static net.codefinch.jev.SystemOneResponse hit(
      Questions q, String line, double p, double exists) {
    ChoiceQuestion where = (ChoiceQuestion) q.asMap().get("where");
    return ScriptedAnswers.neutral(q)
        .choice("where", where, line, p, 0.8)
        .noul("exists", exists)
        .build();
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
