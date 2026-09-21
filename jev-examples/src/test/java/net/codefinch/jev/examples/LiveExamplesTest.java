package net.codefinch.jev.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import net.codefinch.jev.JevClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in: runs every example against the real API ({@code JEV_RUN_LIVE_TESTS=1} and {@code
 * TYPESAFE_API_KEY}). Asserts only that each completes; the printed decisions are for a person to
 * judge the question quality.
 */
@EnabledIfEnvironmentVariable(named = "JEV_RUN_LIVE_TESTS", matches = "1")
class LiveExamplesTest {

  @Test
  void everyExampleRunsAgainstTheLiveApi() {
    for (AllExamples.Example example : AllExamples.ALL) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (JevClient client = JevClient.fromEnv()) {
        example.run().accept(client, new PrintStream(buffer, true, StandardCharsets.UTF_8));
      }
      String out = buffer.toString(StandardCharsets.UTF_8);
      System.out.print(out);
      assertThat(out).as(example.name()).isNotBlank();
    }
  }
}
