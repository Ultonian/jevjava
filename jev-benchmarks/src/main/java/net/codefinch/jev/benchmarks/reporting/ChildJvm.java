package net.codefinch.jev.benchmarks.reporting;

import java.nio.file.Path;

/** Shared child JVM location and descendant cleanup; each runner owns its watchdog and evidence. */
final class ChildJvm {
  private ChildJvm() {}

  static String executable() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  static void stop(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) {
      process.destroyForcibly();
    }
  }
}
