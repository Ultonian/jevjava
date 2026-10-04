package net.codefinch.jev.benchmarks.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.config.JvmSettings;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.benchmarks.load.LoadCase;
import net.codefinch.jev.benchmarks.load.LoadSettings;
import net.codefinch.jev.benchmarks.load.LoadTrial;
import net.codefinch.jev.benchmarks.server.LoopbackServer;
import net.codefinch.jev.internal.Json;

/**
 * Records selected HTTP workloads in isolated child JVMs with fixed, allowlisted launch options.
 */
public final class RunLoad {
  private RunLoad() {}

  /**
   * Usage: {@code RunLoad OUTPUT
   * smoke|baseline|diagnostic|diagnostic-control|pilot|diagnostic-long|diagnostic-long-control
   * representative|core|matrix|offsets|pinning|async-executor|immediate [forward|reverse]}.
   */
  public static void main(String[] args)
      throws IOException, InterruptedException, URISyntaxException {
    if (args.length != 3 && args.length != 4) {
      throw new IllegalArgumentException(
          "Expected OUTPUT MODE GROUP [forward|reverse]; see jev-benchmarks/README.md");
    }
    Path jar = Path.of(RunLoad.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException("Run the packaged benchmarks.jar");
    }
    run(
        jar,
        Path.of(args[0]).toAbsolutePath(),
        args[1],
        args[2],
        args.length == 4 ? args[3] : "forward");
  }

  static List<LoadCase> orderedCells(String mode, String group, String order) {
    List<LoadCase> cells = LoadCase.selection(group);
    if (!LoadSettings.MODES.contains(mode)
        || (mode.startsWith("diagnostic-long") && !group.equals("representative"))
        || !List.of("forward", "reverse").contains(order)
        || (order.equals("reverse") && !List.of("async-executor", "immediate").contains(group))
        || (mode.equals("pilot") && !List.of("async-executor", "immediate").contains(group))
        || (List.of("async-executor", "immediate").contains(group)
            && !List.of("smoke", "pilot").contains(mode))
        || (!mode.startsWith("diagnostic")
            && (group.equals("offsets") || group.equals("pinning")))) {
      throw new IllegalArgumentException("Invalid load mode/group/order combination");
    }
    return order.equals("reverse") ? cells.reversed() : cells;
  }

  static void run(Path jar, Path output, String mode, String group)
      throws IOException, InterruptedException {
    run(jar, output, mode, group, "forward");
  }

  static void run(Path jar, Path output, String mode, String group, String order)
      throws IOException, InterruptedException {
    final List<LoadCase> cells = orderedCells(mode, group, order);
    Path parent = output.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.createDirectory(output);
    final int forks = mode.equals("baseline") ? 3 : 1;
    List<String> vm = vmOptions(mode);
    Map<String, Object> settings = settings(mode, group, order, cells, forks);
    Map<String, Object> manifest = RunMetadata.create(jar, settings, vm);
    RunMetadata.writeJson(output.resolve("manifest.json"), manifest);
    List<Object> trials = new ArrayList<>();
    try {
      for (int fork = 1; fork <= forks; fork++) {
        for (LoadCase cell : cells) {
          trials.add(runTrial(jar, output, mode, vm, cell, fork));
        }
      }
      RunMetadata.writeJson(output.resolve("results.json"), Map.of("trials", trials));
      Files.writeString(output.resolve("summary.md"), summary(Json.toTree(trials), mode));
      manifest.put(
          "resultSha256", Payloads.hash(Files.readAllBytes(output.resolve("results.json"))));
      manifest.put("status", "complete");
    } finally {
      if (!"complete".equals(manifest.get("status"))) {
        manifest.put("status", "failed");
      }
      manifest.put("finishedAt", Instant.now().toString());
      RunMetadata.writeJson(output.resolve("manifest.json"), manifest);
    }
  }

  private static List<String> vmOptions(String mode) {
    List<String> vm =
        new ArrayList<>(
            List.of(
                JvmSettings.MIN_HEAP,
                JvmSettings.MAX_HEAP,
                JvmSettings.GC,
                "-Dsun.net.httpserver.nodelay=true",
                "-Djdk.httpserver.maxConnections=512"));
    if (mode.equals("diagnostic") && Runtime.version().feature() == 21) {
      vm.add("-Djdk.tracePinnedThreads=short");
    }
    return vm;
  }

  private static Map<String, Object> settings(
      String mode, String group, String order, List<LoadCase> cells, int forks) {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put(
        "protocol", group.equals("immediate") ? "immediate-v1" : "stage2-measured-window-v2");
    settings.put("warmupMillis", LoadTrial.warmup(mode).toMillis());
    settings.put("measurementMillis", LoadTrial.measurement(mode).toMillis());
    if (!group.equals("immediate")) {
      settings.put("handlerCpuSamplingInterval", LoopbackServer.CPU_SAMPLING_INTERVAL);
    }
    settings.put("mode", mode);
    settings.put("group", group);
    settings.put("forks", forks);
    settings.put("cells", cells);
    if (mode.startsWith("diagnostic-long")) {
      settings.put(
          "diagnosticProfile", "restricted-long.jfc; 10 ms execution sampling; no pin trace");
    }
    if (List.of("async-executor", "immediate").contains(group)) {
      settings.put("order", order);
    }
    return settings;
  }

  private static Map<String, Object> runTrial(
      Path jar, Path output, String mode, List<String> vm, LoadCase cell, int fork)
      throws IOException, InterruptedException {
    final Path trial = Files.createDirectory(output.resolve(cell.id() + "-fork" + fork));
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("submission", cell.submission());
    config.put("concurrency", cell.concurrency());
    config.put("variant", cell.variant());
    config.put("control", cell.control());
    config.put("mode", mode);
    RunMetadata.writeJson(trial.resolve("config.json"), config);
    List<String> command = new ArrayList<>();
    command.add(ChildJvm.executable());
    command.addAll(vm);
    command.addAll(
        List.of(
            "-cp",
            jar.toString(),
            LoadTrial.class.getName(),
            trial.resolve("config.json").toString(),
            trial.toString()));
    System.out.println("Running " + cell.id() + " fork " + fork + "; " + trial);
    runChild(command, trial.resolve("console.log"), 120);
    JsonNode result = Json.parse(Files.readString(trial.resolve("results.json")));
    if (!result.path("status").asText().equals("complete")) {
      throw new IllegalStateException("Incomplete trial");
    }
    LoadTrial.validate(result.path("measured"), cell);
    return Map.of(
        "cell",
        cell,
        "fork",
        fork,
        "directory",
        cell.id() + "-fork" + fork,
        "resultSha256",
        Payloads.hash(Files.readAllBytes(trial.resolve("results.json"))),
        "result",
        result);
  }

  static void runChild(List<String> command, Path console, int timeoutSeconds)
      throws IOException, InterruptedException {
    runChild(command, console, timeoutSeconds, Map.of());
  }

  static void runChild(
      List<String> command, Path console, int timeoutSeconds, Map<String, String> extraEnvironment)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command);
    Map<String, String> clean = RunBenchmarks.cleanEnvironment(builder.environment());
    builder.environment().clear();
    builder.environment().putAll(clean);
    builder.environment().putAll(extraEnvironment);
    Process process = builder.redirectErrorStream(true).redirectOutput(console.toFile()).start();
    Thread hook = new Thread(() -> ChildJvm.stop(process), "load-cleanup");
    try {
      Runtime.getRuntime().addShutdownHook(hook);
    } catch (IllegalStateException e) {
      ChildJvm.stop(process);
      throw e;
    }
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Trial watchdog expired; see " + console);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("Trial failed; see " + console);
      }
    } finally {
      ChildJvm.stop(process);
      try {
        Runtime.getRuntime().removeShutdownHook(hook);
      } catch (IllegalStateException ignored) {
        /* JVM shutdown already owns the hook. */
      }
    }
  }

  static String summary(JsonNode trials, String mode) {
    StringBuilder text = new StringBuilder("# Local HTTP measurements\n\n");
    text.append(
        mode.equals("smoke")
            ? "Smoke validates execution only.\n\n"
            : mode.equals("pilot")
                ? "Exploratory lifecycle/executor pilot; one fresh JVM per cell, no transport"
                    + " controls or regression verdict.\n\n"
                : mode.startsWith("diagnostic")
                    ? "Diagnostic instrumentation enabled; do not compare as baseline"
                        + " throughput.\n\n"
                    : "Initial closed-loop measurements, not a regression threshold or absolute SDK"
                        + " capacity.\n\n");
    text.append(
        "Resources combine driver and selected client/transport (including server for HTTP cells)."
            + " Full outcome histograms, drain counts, resource data and diagnostics are in"
            + " results.json.\n\n");
    text.append(
        "| Cell | Fork | Completed in window | Drain completions | Success/s | Terminal/s | p99 µs"
            + " | Mean executor queue µs | Headroom evidence |\n"
            + "|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
    for (JsonNode row : trials) {
      JsonNode config = row.path("cell");
      LoadCase cell =
          new LoadCase(
              LoadCase.Submission.valueOf(config.path("submission").asText()),
              config.path("concurrency").asInt(),
              LoadCase.Variant.valueOf(config.path("variant").asText()),
              config.path("control").asBoolean());
      JsonNode measured = row.path("result").path("measured");
      String headroom = Headroom.describe(trials, row, cell, mode);
      text.append("| ")
          .append(cell.id())
          .append(" | ")
          .append(row.path("fork").asInt())
          .append(" | ")
          .append(measured.path("completedInWindow").asLong())
          .append(" | ")
          .append(measured.path("drainCompletions").asLong())
          .append(" | ")
          .append(
              String.format(Locale.ROOT, "%.2f", measured.path("successfulPerSecond").asDouble()))
          .append(" | ")
          .append(String.format(Locale.ROOT, "%.2f", measured.path("terminalPerSecond").asDouble()))
          .append(" | ")
          .append(
              measured
                  .path("latencyByOutcome")
                  .path(
                      cell.status() == 200
                          ? "SUCCESS"
                          : cell.status() < 500 ? "HTTP4XX" : "HTTP5XX")
                  .path("p99Micros"))
          .append(" | ")
          .append(
              cell.variant() == LoadCase.Variant.IMMEDIATE
                  ? "n/a"
                  : String.format(Locale.ROOT, "%.2f", Headroom.meanQueue(row) / 1000))
          .append(" | ")
          .append(headroom)
          .append(" |\n");
    }
    return text.toString();
  }
}
