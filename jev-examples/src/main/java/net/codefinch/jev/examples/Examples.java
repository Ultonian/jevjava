package net.codefinch.jev.examples;

import java.io.PrintStream;
import net.codefinch.jev.JevClient;

/** Shared plumbing for the examples: which client to use, and a little formatting. */
final class Examples {
  private Examples() {}

  /** The live client when {@code TYPESAFE_API_KEY} is set, otherwise the example's fake. */
  static JevClient clientOr(JevClient fake) {
    String key = System.getenv("TYPESAFE_API_KEY");
    if (key == null || key.isBlank()) {
      return fake;
    }
    fake.close();
    return JevClient.fromEnv();
  }

  static String source() {
    String key = System.getenv("TYPESAFE_API_KEY");
    return key == null || key.isBlank() ? "recording fake" : "live API";
  }

  static void heading(PrintStream out, String title, String docsPage) {
    out.println();
    out.println("== " + title + "  (docs: https://docs.typesafe.ai/" + docsPage + ")");
  }

  static String clip(String s, int width) {
    String one = s.replace('\n', ' ');
    return one.length() > width ? one.substring(0, width - 1) + "…" : one;
  }
}
