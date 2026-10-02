package net.codefinch.jev.benchmarks.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.internal.Json;

/** Runs a controlled JMH process and saves its results, identities and allowlisted metadata. */
public final class RunBenchmarks {
  private static final List<String> VM_OPTIONS = List.of("-Xms512m", "-Xmx512m", "-XX:+UseG1GC");

  private RunBenchmarks() {}

  /**
   * Usage: {@code RunBenchmarks OUTPUT_DIRECTORY smoke|baseline timing|gc
   * all|core|ticket|sizes|structure}.
   *
   * <p>The output directory must not exist. Run from the repository root to record Git identity.
   */
  public static void main(String[] args)
      throws IOException, InterruptedException, URISyntaxException {
    if (args.length != 4) {
      throw new IllegalArgumentException(
          "Usage: RunBenchmarks OUTPUT_DIRECTORY smoke|baseline timing|gc"
              + " all|core|ticket|sizes|structure");
    }
    final Settings settings = Settings.parse(args[1], args[2], args[3]);
    Path jar =
        Path.of(RunBenchmarks.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException("Run the packaged benchmarks.jar, not IDE classes");
    }
    run(jar, Path.of(args[0]).toAbsolutePath(), settings);
  }

  static void run(Path jar, Path output, Settings settings)
      throws IOException, InterruptedException {
    // Resolve and validate the matrix before creating output or starting a long measurement.
    BenchmarkResults.expectedCells(settings);
    Path parent = output.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.createDirectory(output); // Never overwrite an earlier run.
    Map<String, Object> manifest = RunMetadata.create(jar, settings, VM_OPTIONS);
    RunMetadata.writeJson(output.resolve("manifest.json"), manifest);
    List<String> command = command(jar, output, settings);
    ProcessBuilder builder = new ProcessBuilder(command);
    Map<String, String> environment = cleanEnvironment(builder.environment());
    builder.environment().clear();
    builder.environment().putAll(environment);
    builder.redirectErrorStream(true).redirectOutput(output.resolve("console.log").toFile());
    System.out.println("Running " + settings + "; results: " + output);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      manifest.put("status", "failed");
      manifest.put("finishedAt", Instant.now().toString());
      RunMetadata.writeJson(output.resolve("manifest.json"), manifest);
      throw e;
    }
    Thread cleanup = new Thread(() -> stop(process), "benchmark-cleanup");
    Runtime.getRuntime().addShutdownHook(cleanup);
    try {
      if (!process.waitFor(60, TimeUnit.MINUTES)) {
        throw new IllegalStateException("JMH exceeded the 60-minute watchdog");
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException(
            "JMH failed; see console.log (exit " + process.exitValue() + ")");
      }
      JsonNode results = Json.parse(Files.readString(output.resolve("results.json")));
      BenchmarkResults.validate(results, settings);
      Files.writeString(
          output.resolve("summary.md"),
          BenchmarkResults.summary(results, settings),
          StandardCharsets.UTF_8);
      manifest.put("status", "complete");
      manifest.put(
          "resultSha256", Payloads.hash(Files.readAllBytes(output.resolve("results.json"))));
    } finally {
      stop(process);
      try {
        Runtime.getRuntime().removeShutdownHook(cleanup);
      } catch (IllegalStateException ignored) {
        // Shutdown has already begun; retain the original failure and finalize the manifest.
      }
      manifest.put("finishedAt", Instant.now().toString());
      if (!"complete".equals(manifest.get("status"))) {
        manifest.put("status", "failed");
      }
      RunMetadata.writeJson(output.resolve("manifest.json"), manifest);
    }
    System.out.println("Complete; review " + output.resolve("summary.md"));
  }

  static List<String> command(Path jar, Path output, Settings settings) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(VM_OPTIONS);
    command.addAll(
        List.of(
            "-jar",
            jar.toAbsolutePath().toString(),
            settings.include(),
            "-f",
            settings.smoke() ? "1" : "3",
            "-wi",
            settings.smoke() ? "1" : "5",
            "-i",
            settings.smoke() ? "1" : "5",
            "-w",
            settings.smoke() ? "100ms" : "1s",
            "-r",
            settings.smoke() ? "100ms" : "1s",
            "-t",
            "1",
            "-foe",
            "true",
            "-jvmArgs",
            String.join(" ", VM_OPTIONS),
            "-rf",
            "json",
            "-rff",
            output.resolve("results.json").toString()));
    if (settings.gc()) {
      command.addAll(List.of("-prof", "gc"));
    }
    if (!settings.scenarios().isEmpty()) {
      command.addAll(List.of("-p", "scenario=" + String.join(",", settings.scenarios())));
    }
    return List.copyOf(command);
  }

  static Map<String, String> cleanEnvironment(Map<String, String> source) {
    Map<String, String> clean = new LinkedHashMap<>();
    // No API credentials, JVM injection options or arbitrary inherited variables in JMH forks.
    for (String key : List.of("PATH", "SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR")) {
      if (source.containsKey(key)) {
        clean.put(key, source.get(key));
      }
    }
    clean.put("LANG", "C.UTF-8");
    return clean;
  }

  private static void stop(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) {
      process.destroyForcibly();
    }
  }

  /** Closed set of launch options; arbitrary JVM flags are deliberately not forwarded. */
  public record Settings(boolean smoke, boolean gc, String group) {
    /** Reject invalid programmatic options as well as invalid CLI options. */
    public Settings {
      if (!List.of("all", "core", "ticket", "sizes", "structure").contains(group)) {
        throw new IllegalArgumentException("Unknown benchmark group: " + group);
      }
    }

    String include() {
      return switch (group) {
        case "ticket", "sizes" -> ".*WireBenchmarks.*";
        case "structure" -> ".*(Wire|Construction)Benchmarks.*";
        case "core" -> ".*(Wire|Construction|RejectedResponse)Benchmarks.*";
        default -> ".*benchmarks.components.*";
      };
    }

    List<String> scenarios() {
      return switch (group) {
        case "ticket" -> List.of("ticket");
        case "sizes" -> List.of("content16k", "content256k");
        case "structure" -> List.of("structured16k");
        default -> List.of();
      };
    }

    /** Validates command-line modes before launching or writing output. */
    public static Settings parse(String mode, String profiler, String group) {
      if (!List.of("smoke", "baseline").contains(mode)
          || !List.of("timing", "gc").contains(profiler)
          || !List.of("all", "core", "ticket", "sizes", "structure").contains(group)) {
        throw new IllegalArgumentException(
            "Expected smoke|baseline timing|gc all|core|ticket|sizes|structure");
      }
      return new Settings(mode.equals("smoke"), profiler.equals("gc"), group);
    }
  }
}
