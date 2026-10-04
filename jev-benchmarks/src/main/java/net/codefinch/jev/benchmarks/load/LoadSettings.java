package net.codefinch.jev.benchmarks.load;

import java.time.Duration;
import java.util.Set;

/** Execution settings whose recorded values must match the running load session. */
public final class LoadSettings {
  public static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(15);
  public static final Duration DEADLINE = Duration.ofSeconds(20);
  public static final Duration DRAIN = Duration.ofSeconds(5);
  public static final Set<String> MODES =
      Set.of(
          "smoke",
          "baseline",
          "diagnostic",
          "diagnostic-control",
          "pilot",
          "diagnostic-long",
          "diagnostic-long-control");

  private LoadSettings() {}
}
