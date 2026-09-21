package net.codefinch.jev.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.codefinch.jev.ChoiceQuestion;
import net.codefinch.jev.Content;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.test.RecordingJevClient;
import org.junit.jupiter.api.Test;

/**
 * Every example runs end-to-end against its scripted fake, and the decisions come out as intended.
 */
class ExamplesTest {

  private static String run(
      RecordingJevClient fake,
      java.util.function.BiConsumer<net.codefinch.jev.JevClient, PrintStream> runner) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (fake) {
      runner.accept(fake, new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  void supportTicketFanOutReadsOnlyTheRelevantSpeculativeAnswers() {
    RecordingJevClient fake = SupportTicketFanOut.scripted();
    String out = run(fake, SupportTicketFanOut::run);
    assertThat(out).contains("billing", "refund workflow", "[frustrated]");
    assertThat(out).contains("bug_report", "engineering on-call (blocking, with repro steps)");
    assertThat(out).contains("feature_request", "product backlog");
    assertThat(fake.requests()).hasSize(3);
    assertThat(fake.requests().get(0).questions().ids())
        .as("all five questions go in every call, speculative ones included")
        .containsExactly(
            "category",
            "bug_severity",
            "has_reproducible_steps",
            "refund_requested",
            "frustration");
  }

  @Test
  void resumeScreeningRanksTheSameAnswersDifferentlyPerRole() {
    RecordingJevClient fake = ResumeScreening.scripted();
    String out = run(fake, ResumeScreening::run);
    int ic = out.indexOf("Senior IC ranking:");
    int em = out.indexOf("Engineering manager ranking:");
    String icBlock = out.substring(ic, em);
    String emBlock = out.substring(em);
    assertThat(icBlock.indexOf("Amara"))
        .as("Amara first for senior IC")
        .isLessThan(icBlock.indexOf("Ben"));
    assertThat(emBlock.indexOf("Ben"))
        .as("Ben first for engineering manager")
        .isLessThan(emBlock.indexOf("Amara"));
    assertThat(fake.requests()).hasSize(3);
    assertThat(fake.requests().get(0).state().content().toJson().get("resume").textValue())
        .contains("Staff engineer");
    assertThat(
            ResumeScreening.SENIOR_IC.weights().values().stream()
                .mapToDouble(Double::doubleValue)
                .sum())
        .isEqualTo(1.0);
  }

  @Test
  void voiceBankingGatesTransfersHarderThanBalances() {
    String out = run(VoiceBanking.scripted(), VoiceBanking::run);
    List<String> lines = out.lines().filter(l -> l.contains("->")).toList();
    assertThat(lines.get(0)).endsWith("show the balance");
    assertThat(lines.get(1)).endsWith("approve the transfer");
    assertThat(lines.get(2)).endsWith("ask the user to confirm the transfer");
    assertThat(lines.get(3)).endsWith("support agent");
    assertThat(VoiceBanking.TRANSFER.high()).isGreaterThan(VoiceBanking.BALANCE.high());
  }

  @Test
  void intentRoutingPicksTheCheapestCapableHandler() {
    String out = run(IntentRouting.scripted(), IntentRouting::run);
    List<String> lines = out.lines().filter(l -> l.contains("->")).toList();
    assertThat(lines.get(0)).endsWith("order database lookup");
    assertThat(lines.get(1)).endsWith("LLM with product catalogue context");
    assertThat(lines.get(2)).endsWith("returns workflow");
    assertThat(lines.get(3)).endsWith("human agent (complex)");
  }

  @Test
  void lineSearchPointsAtLinesAndDeclinesWhenNothingAnswers() {
    RecordingJevClient fake = LineSearch.scripted();
    String out = run(fake, LineSearch::run);
    assertThat(out).contains("How do I wash it?", "L3 (p=0.86): Machine wash cold");
    assertThat(out).contains("How much does it weigh?", "L6 (p=0.91): Weight: 215 g");
    assertThat(out).contains("Is it available in blue?", "no answer in the document");
    SystemOneRequest first = fake.requests().get(0);
    assertThat(first.state().content().toJson().get("lines")).hasSize(LineSearch.DOCUMENT.size());
    ChoiceQuestion where = (ChoiceQuestion) first.questions().asMap().get("where");
    assertThat(where.criteria().options()).hasSize(LineSearch.DOCUMENT.size());
    assertThat(where.criteria().options().values())
        .as("line ids are undescribed")
        .allMatch(Content::isNull);
    assertThat(first.questions().ids()).containsExactly("where", "exists");
  }

  @Test
  void rerankAsksOneBoundQuestionPerCandidateInOneCallAndSortsByProbability() {
    RecordingJevClient fake = Rerank.scripted();
    String out = run(fake, Rerank::run);
    List<String> ranked = out.lines().filter(l -> l.trim().matches("0\\.\\d\\d  .*")).toList();
    assertThat(ranked).hasSize(2);
    assertThat(ranked.get(0)).contains("0.93", "Returns accepted within 30 days");
    assertThat(ranked.get(1)).contains("0.71", "Exchanges for a different size");
    SystemOneRequest request = fake.requests().get(0);
    assertThat(fake.requests()).as("one call for the whole shortlist").hasSize(1);
    assertThat(request.questions().size()).isEqualTo(Rerank.SAMPLE_SHORTLIST.size());
    assertThat(request.state().content().toJson().get("query").textValue())
        .isEqualTo(Rerank.SAMPLE_QUERY);
    var instructions =
        request.questions().asMap().get("item_1").instructions().orElseThrow().toJson();
    assertThat(instructions.get("item").textValue()).isEqualTo(Rerank.SAMPLE_SHORTLIST.get(1));
    assertThat(instructions.get("task").textValue()).isEqualTo(Rerank.TASK);
  }

  @Test
  void entityAlignmentChoosesMergeCuratorOrUnlinked() {
    RecordingJevClient fake = EntityAlignment.scripted();
    String out = run(fake, EntityAlignment::run);
    assertThat(out).contains("p1   assert sameAs", "abv equal");
    assertThat(out).contains("p2   curator queue", "abv differs");
    assertThat(out).contains("p3   leave unlinked");
    assertThat(
            fake.requests()
                .get(0)
                .state()
                .content()
                .toJson()
                .get("entity_a")
                .get("abv")
                .doubleValue())
        .isEqualTo(5.4);
    assertThat(fake.requests().get(0).questions().ids())
        .containsExactly("link_state", "same_name", "same_brewery", "same_style");
  }

  @Test
  void allExamplesRunnerCoversEveryExampleAndRunsWithoutKey() {
    assertThat(AllExamples.ALL)
        .extracting(AllExamples.Example::name)
        .containsExactly(
            "TicketTriage",
            "SupportTicketFanOut",
            "ResumeScreening",
            "VoiceBanking",
            "IntentRouting",
            "LineSearch",
            "Rerank",
            "EntityAlignment");
    if (System.getenv("TYPESAFE_API_KEY") == null) {
      AllExamples.main(new String[0]);
    }
  }
}
