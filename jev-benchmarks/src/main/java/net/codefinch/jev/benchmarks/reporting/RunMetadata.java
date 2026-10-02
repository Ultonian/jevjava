package net.codefinch.jev.benchmarks.reporting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.internal.Json;

/** Allowlisted environment and artifact identity, never arbitrary properties or credentials. */
final class RunMetadata {
  private RunMetadata() {}

  static Map<String, Object> create(Path jar, Object settings, List<String> vmOptions)
      throws IOException, InterruptedException {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("startedAt", Instant.now().toString());
    result.put("status", "running");
    result.put("settings", settings);
    result.put(
        "configurationSha256",
        Payloads.hash(
            (Json.write(Json.toTree(settings)) + "\n" + String.join(" ", vmOptions))
                .getBytes(StandardCharsets.UTF_8)));
    result.put("benchmarkJarSha256", Payloads.hash(Files.readAllBytes(jar)));
    result.put("gitCommit", git("rev-parse", "HEAD"));
    String status = git("status", "--porcelain", "--untracked-files=normal");
    result.put("gitDirty", status.equals("unavailable") ? "unknown" : !status.isBlank());
    result.put("javaVersion", System.getProperty("java.version"));
    result.put("javaVendor", System.getProperty("java.vendor"));
    result.put("vmVersion", System.getProperty("java.vm.version"));
    result.put("os", System.getProperty("os.name"));
    result.put("osVersion", System.getProperty("os.version"));
    result.put("architecture", System.getProperty("os.arch"));
    result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    result.put("vmOptions", vmOptions);
    result.put("cpuModel", cpuModel());
    result.put("cgroupV2", cgroupLimits());
    result.put("fixtureProvenance", Payloads.readTree("provenance.json"));
    result.put("fixtures", Payloads.inventory());
    return result;
  }

  private static Map<String, String> cgroupLimits() throws IOException {
    // Linux kernel metadata paths are intentionally fixed; unavailable on other platforms.
    return Map.of(
        "cpuMax",
        optionalFile(Path.of("/sys/fs/cgroup", "cpu.max")),
        "memoryMax",
        optionalFile(Path.of("/sys/fs/cgroup", "memory.max")),
        "cpuset",
        optionalFile(Path.of("/sys/fs/cgroup", "cpuset.cpus.effective")));
  }

  private static String git(String... args) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    Process process;
    try {
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      return "unavailable";
    }
    // Both commands produce bounded output for this repository; discard status filenames later.
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return process.waitFor() == 0 ? output.strip() : "unavailable";
  }

  private static String cpuModel() throws IOException {
    Path file = Path.of("/proc/cpuinfo");
    if (!Files.isReadable(file)) {
      return "unavailable";
    }
    try (var lines = Files.lines(file)) {
      return lines
          .filter(line -> line.startsWith("model name"))
          .map(line -> line.substring(line.indexOf(':') + 1).strip())
          .findFirst()
          .orElse("unavailable");
    }
  }

  private static String optionalFile(Path path) throws IOException {
    return Files.isReadable(path) ? Files.readString(path).strip() : "unavailable";
  }

  static void writeJson(Path target, Map<String, Object> value) throws IOException {
    Files.writeString(target, Json.toTree(value).toPrettyString() + "\n", StandardCharsets.UTF_8);
  }
}
