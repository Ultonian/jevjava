package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.codefinch.jev.Answers;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.Questions;
import net.codefinch.jev.ScoreQuestion;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneResponse;
import net.codefinch.jev.patterns.Composite;
import net.codefinch.jev.test.RecordingJevClient;
import net.codefinch.jev.test.ScriptedAnswers;

/**
 * Composite scoring (docs: patterns/composite-scoring): score independent dimensions once, then
 * combine them with role-specific weights in code. The same four answers rank candidates for a
 * senior IC role and an engineering-manager role differently.
 */
public final class ResumeScreening {

  static final Questions QUESTIONS =
      Questions.builder()
          .score(
              "python_depth",
              "How much depth of python experience does this candidate have, based on the supplied"
                  + " resume?",
              List.of(
                  "No Python experience mentioned",
                  "Mentioned but no detail",
                  "Used in projects, some specifics",
                  "Primary language, multiple projects",
                  "Deep expertise: architecture, performance, libraries"))
          .score(
              "team_leadership",
              "How much experience does this candidate have managing or leading engineering teams?",
              List.of(
                  "No management experience mentioned",
                  "Informal mentorship or tech lead role",
                  "Led a small team or project",
                  "Managed a team with direct reports",
                  "Managed multiple teams or an engineering org"))
          .score(
              "system_design",
              "How much experience does this candidate have designing large-scale or distributed"
                  + " systems?",
              List.of(
                  "No architecture work mentioned",
                  "Contributed to design discussions",
                  "Designed components of a larger system",
                  "Owned architecture of a significant system",
                  "Designed systems at scale across multiple domains"))
          .score(
              "generalist",
              "How much evidence is there that this candidate picks up unfamiliar tools, roles, or"
                  + " domains outside their core specialty?",
              List.of(
                  "Only one domain or role mentioned",
                  "Some variety but within a narrow field",
                  "Worked across a few different areas or tech stacks",
                  "Regularly moved between domains, wore many hats",
                  "Track record of ramping up in unfamiliar areas and delivering"))
          .build();

  /**
   * Weights from the docs page: what each role values. Composite normalises each score to [0, 1].
   */
  static final Composite SENIOR_IC =
      Composite.score(
          Map.of(
              "python_depth",
              0.40,
              "team_leadership",
              0.10,
              "system_design",
              0.40,
              "generalist",
              0.10));

  static final Composite ENGINEERING_MANAGER =
      Composite.score(
          Map.of(
              "python_depth",
              0.15,
              "team_leadership",
              0.40,
              "system_design",
              0.20,
              "generalist",
              0.25));

  private ResumeScreening() {}

  record Candidate(String name, String resume) {}

  record Screened(Candidate candidate, double seniorIc, double engineeringManager) {}

  /** Scores every candidate concurrently (one call each), then combines in code. */
  static List<Screened> screen(JevClient client, List<Candidate> candidates) {
    List<CompletableFuture<SystemOneResponse>> futures = new ArrayList<>();
    for (Candidate c : candidates) {
      futures.add(client.systemOneAsync(State.of(Map.of("resume", c.resume())), QUESTIONS));
    }
    List<Screened> out = new ArrayList<>();
    for (int i = 0; i < candidates.size(); i++) {
      Answers a = futures.get(i).join().answers();
      out.add(new Screened(candidates.get(i), SENIOR_IC.apply(a), ENGINEERING_MANAGER.apply(a)));
    }
    return out;
  }

  static final List<Candidate> SAMPLE_CANDIDATES =
      List.of(
          new Candidate(
              "Amara",
              "Staff engineer, 9 years Python. Designed the ingestion platform (Kafka, 40k msg/s),"
                  + " wrote the internal async framework, mentors juniors informally."),
          new Candidate(
              "Ben",
              "Engineering manager, 3 teams / 18 reports. Earlier: backend dev in Java and Go, some"
                  + " Python scripting. Ran the migration to Kubernetes and the on-call rotation."),
          new Candidate(
              "Chen",
              "Full-stack developer, 4 years. React and Django; led a 3-person project to rebuild"
                  + " checkout. Picked up data engineering last year to unblock analytics."));

  static void run(JevClient client, PrintStream out) {
    Examples.heading(out, "Resume screening (composite scoring)", "patterns/composite-scoring");
    List<Screened> screened = screen(client, SAMPLE_CANDIDATES);
    out.println("Senior IC ranking:");
    screened.stream()
        .sorted(Comparator.comparingDouble(Screened::seniorIc).reversed())
        .forEach(s -> out.printf("  %-6s %.2f%n", s.candidate().name(), s.seniorIc()));
    out.println("Engineering manager ranking:");
    screened.stream()
        .sorted(Comparator.comparingDouble(Screened::engineeringManager).reversed())
        .forEach(s -> out.printf("  %-6s %.2f%n", s.candidate().name(), s.engineeringManager()));
  }

  static RecordingJevClient scripted() {
    ScoreQuestion py = (ScoreQuestion) QUESTIONS.asMap().get("python_depth");
    ScoreQuestion lead = (ScoreQuestion) QUESTIONS.asMap().get("team_leadership");
    ScoreQuestion arch = (ScoreQuestion) QUESTIONS.asMap().get("system_design");
    ScoreQuestion gen = (ScoreQuestion) QUESTIONS.asMap().get("generalist");
    return new RecordingJevClient()
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("python_depth", py, 4.0, 0.9)
                .score("team_leadership", lead, 1.0, 0.8)
                .score("system_design", arch, 3.6, 0.85)
                .score("generalist", gen, 1.5, 0.6))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("python_depth", py, 1.2, 0.7)
                .score("team_leadership", lead, 4.0, 0.95)
                .score("system_design", arch, 2.5, 0.7)
                .score("generalist", gen, 2.8, 0.7))
        .enqueue(
            ScriptedAnswers.neutral(QUESTIONS)
                .score("python_depth", py, 2.4, 0.7)
                .score("team_leadership", lead, 2.0, 0.7)
                .score("system_design", arch, 1.8, 0.6)
                .score("generalist", gen, 3.2, 0.8));
  }

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    try (JevClient client = Examples.clientOr(scripted())) {
      System.out.println("Source: " + Examples.source());
      run(client, System.out);
    }
  }
}
