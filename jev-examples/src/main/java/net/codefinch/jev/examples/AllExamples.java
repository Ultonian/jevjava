package net.codefinch.jev.examples;

import java.io.PrintStream;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.test.RecordingJevClient;

/**
 * Runs every example in turn: {@code ./mvnw -q -pl jev-examples exec:java
 * -Dexec.mainClass=net.codefinch.jev.examples.AllExamples}.
 */
public final class AllExamples {

  /** One example: its fake and its runner. */
  record Example(
      String name, Supplier<RecordingJevClient> scripted, BiConsumer<JevClient, PrintStream> run) {}

  static final List<Example> ALL =
      List.of(
          new Example(
              "TicketTriage",
              TicketTriage::scripted,
              (c, out) -> TicketTriage.run(c, out, Examples.source())),
          new Example(
              "SupportTicketFanOut", SupportTicketFanOut::scripted, SupportTicketFanOut::run),
          new Example("ResumeScreening", ResumeScreening::scripted, ResumeScreening::run),
          new Example("VoiceBanking", VoiceBanking::scripted, VoiceBanking::run),
          new Example("IntentRouting", IntentRouting::scripted, IntentRouting::run),
          new Example("LineSearch", LineSearch::scripted, LineSearch::run),
          new Example("Rerank", Rerank::scripted, Rerank::run),
          new Example("EntityAlignment", EntityAlignment::scripted, EntityAlignment::run));

  private AllExamples() {}

  /** Entry point: the live API when {@code TYPESAFE_API_KEY} is set, otherwise the fake. */
  public static void main(String[] args) {
    System.out.println("Source: " + Examples.source());
    for (Example example : ALL) {
      try (JevClient client = Examples.clientOr(example.scripted().get())) {
        example.run().accept(client, System.out);
      }
    }
  }
}
