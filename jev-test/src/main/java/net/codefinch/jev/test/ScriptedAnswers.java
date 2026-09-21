package net.codefinch.jev.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.codefinch.jev.Answer;
import net.codefinch.jev.Answers;
import net.codefinch.jev.ChoiceAnswer;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.NoulAnswer;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Question;
import net.codefinch.jev.Questions;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.ScoreAnswer;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.Usage;

/**
 * Builds the answers a {@link RecordingJevClient} returns. Start from {@link #neutral(Questions)}
 * (every question answered at its midpoint) or from scratch, override individual ids, and build a
 * {@link SystemOneResponse}. Answers are shaped from the questions, so a choice answer's
 * probabilities cover exactly the requested labels and a score answer's legend matches the rubric.
 */
public final class ScriptedAnswers {
  private final Map<String, Answer> answers = new LinkedHashMap<>();
  private String model = "jev-test";
  private Usage usage = new Usage(0, 0);
  private Map<String, List<String>> headers = Map.of();

  private ScriptedAnswers() {}

  /** An empty script; add answers with the {@code noul}/{@code choice}/{@code score} methods. */
  public static ScriptedAnswers empty() {
    return new ScriptedAnswers();
  }

  /**
   * A neutral answer for every question: noul 0.5; choice = the first label with uniform
   * probabilities; score = the midpoint level with uniform probabilities; confidence 0.5.
   */
  public static ScriptedAnswers neutral(Questions questions) {
    Objects.requireNonNull(questions, "questions");
    ScriptedAnswers s = new ScriptedAnswers();
    questions.asMap().forEach((id, q) -> s.answers.put(id, neutralAnswer(q)));
    return s;
  }

  /** The neutral answer for one question. */
  public static Answer neutralAnswer(Question question) {
    return switch (question) {
      case NoulQuestion n -> new NoulAnswer(0.5);
      case ChoiceQuestion c -> {
        List<String> labels = List.copyOf(c.criteria().options().keySet());
        if (labels.isEmpty()) {
          throw new IllegalArgumentException("choice question has no options to answer with");
        }
        yield uniformChoice(labels, labels.get(0), 0.5);
      }
      case ScoreQuestion sq -> scoreAnswer(sq, (sq.criteria().size() - 1) / 2.0, 0.5);
    };
  }

  /** Sets a noul answer. */
  public ScriptedAnswers noul(String id, double probability) {
    answers.put(id, new NoulAnswer(probability));
    return this;
  }

  /**
   * Sets a choice answer for a question, putting {@code probability} on {@code label} and the
   * remainder spread evenly over the other labels.
   */
  public ScriptedAnswers choice(
      String id, ChoiceQuestion question, String label, double probability, double confidence) {
    List<String> labels = List.copyOf(question.criteria().options().keySet());
    if (!labels.contains(label)) {
      throw new IllegalArgumentException(
          "label '" + label + "' is not an option of question '" + id + "'");
    }
    Map<String, Double> probabilities = new LinkedHashMap<>();
    double rest = labels.size() == 1 ? 0 : (1 - probability) / (labels.size() - 1);
    for (String l : labels) {
      probabilities.put(l, l.equals(label) ? probability : rest);
    }
    answers.put(id, new ChoiceAnswer(label, probabilities, confidence));
    return this;
  }

  /** Sets a choice answer directly. */
  public ScriptedAnswers choice(String id, ChoiceAnswer answer) {
    answers.put(id, answer);
    return this;
  }

  /** Sets a score answer for a question at the given position, with a legend from the rubric. */
  public ScriptedAnswers score(String id, ScoreQuestion question, double score, double confidence) {
    answers.put(id, scoreAnswer(question, score, confidence));
    return this;
  }

  /** Sets a score answer directly. */
  public ScriptedAnswers score(String id, ScoreAnswer answer) {
    answers.put(id, answer);
    return this;
  }

  /** Sets any answer. */
  public ScriptedAnswers put(String id, Answer answer) {
    answers.put(Objects.requireNonNull(id, "id"), Objects.requireNonNull(answer, "answer"));
    return this;
  }

  /** Removes an answer, simulating an unknown answer kind the SDK dropped. */
  public ScriptedAnswers without(String id) {
    answers.remove(id);
    return this;
  }

  /** The model the response reports; default {@code jev-test}. */
  public ScriptedAnswers model(String model) {
    this.model = Objects.requireNonNull(model, "model");
    return this;
  }

  /** The usage the response reports; default zero. */
  public ScriptedAnswers usage(long inputTokens, long outputTokens) {
    this.usage = new Usage(inputTokens, outputTokens);
    return this;
  }

  /** Response headers, e.g. a request id. */
  public ScriptedAnswers headers(Map<String, List<String>> headers) {
    this.headers = Map.copyOf(headers);
    return this;
  }

  /** The answers so far. */
  public Answers answers() {
    return Answers.of(answers);
  }

  /** The response. Its raw body is a compact rendering of the answers. */
  public SystemOneResponse build() {
    Answers built = Answers.of(answers);
    String body = "{\"model\":\"" + model + "\",\"answers\":" + built + ",\"usage\":" + usage + "}";
    return new SystemOneResponse(model, built, usage, ResponseMetadata.of(headers, body));
  }

  private static ChoiceAnswer uniformChoice(List<String> labels, String label, double confidence) {
    Map<String, Double> probabilities = new LinkedHashMap<>();
    for (String l : labels) {
      probabilities.put(l, 1.0 / labels.size());
    }
    return new ChoiceAnswer(label, probabilities, confidence);
  }

  private static ScoreAnswer scoreAnswer(ScoreQuestion question, double score, double confidence) {
    int levels = question.criteria().size();
    Map<Integer, Content> legend = new LinkedHashMap<>();
    Map<Integer, Double> probabilities = new LinkedHashMap<>();
    for (int i = 0; i < levels; i++) {
      legend.put(i, question.criteria().get(i));
      probabilities.put(i, 1.0 / levels);
    }
    return new ScoreAnswer(score, legend, probabilities, confidence);
  }

  /** Convenience: the neutral response for a question set. */
  public static SystemOneResponse neutralResponse(Questions questions) {
    return neutral(questions).build();
  }

  /** Convenience: an optional lookup of a scripted answer. */
  public Optional<Answer> get(String id) {
    return Optional.ofNullable(answers.get(id));
  }
}
