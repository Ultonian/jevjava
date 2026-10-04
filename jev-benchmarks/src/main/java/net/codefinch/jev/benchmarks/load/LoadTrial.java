package net.codefinch.jev.benchmarks.load;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.benchmarks.diagnostics.RestrictedRecording;
import net.codefinch.jev.internal.Json;

/** One fresh-JVM trial, launched only by the controlled reporting runner. */
public final class LoadTrial {
  private LoadTrial() {}

  /** Internal entry point: config JSON and a new trial directory, never an endpoint override. */
  public static void main(String[] args) throws IOException, InterruptedException, ParseException {
    if (args.length != 2) {
      throw new IllegalArgumentException("Expected config file and trial directory");
    }
    if (!Boolean.getBoolean("sun.net.httpserver.nodelay")
        || !"512".equals(System.getProperty("jdk.httpserver.maxConnections"))) {
      throw new IllegalArgumentException(
          "Use RunLoad: TCP_NODELAY and a 512-connection cap are required");
    }
    final Path output = Path.of(args[1]);
    JsonNode config = Json.parse(Files.readString(Path.of(args[0])));
    LoadCase cell =
        new LoadCase(
            LoadCase.Submission.valueOf(config.path("submission").asText()),
            config.path("concurrency").asInt(),
            LoadCase.Variant.valueOf(config.path("variant").asText()),
            config.path("control").asBoolean());
    String mode = config.path("mode").asText();
    if (!LoadSettings.MODES.contains(mode)) {
      throw new IllegalArgumentException("Invalid mode");
    }
    final boolean diagnostic = mode.equals("diagnostic") || mode.equals("diagnostic-long");
    final Duration warmup = warmup(mode);
    final Duration measurement = measurement(mode);
    Map<String, Object> result = metadata(cell, mode);
    try (RestrictedRecording recording =
        diagnostic ? new RestrictedRecording(mode.equals("diagnostic-long")) : null) {
      runCohorts(result, cell, warmup, measurement);
      result.put(
          "cleanup",
          cell.variant() == LoadCase.Variant.IMMEDIATE
              ? "client and synthetic transport closed normally; no server"
              : "client, caller-owned resources and server closed normally");
      if (recording != null) {
        finishRecording(recording, output, result);
      }
      result.put("status", "complete");
    } catch (IOException | InterruptedException | ParseException | RuntimeException e) {
      result.put("status", "failed");
      result.put("failureClass", e.getClass().getName());
      throw e;
    } finally {
      Files.writeString(
          output.resolve("results.json"), Json.toTree(result).toPrettyString() + "\n");
    }
  }

  private static Map<String, Object> metadata(LoadCase cell, String mode) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("cell", cell);
    result.put("mode", mode);
    result.put("measurementKind", "fixed-concurrency closed loop; never open-loop capacity");
    result.put(
        "timeouts",
        Map.of(
            "attemptSeconds",
            LoadSettings.ATTEMPT_TIMEOUT.toSeconds(),
            "deadlineSeconds",
            LoadSettings.DEADLINE.toSeconds(),
            "resultDrainSeconds",
            LoadSettings.DRAIN.toSeconds(),
            "observerDrainSeconds",
            LoadSettings.DRAIN.toSeconds()));
    result.put(
        "operationExecutor",
        cell.control()
            ? "plain JDK transport; no SDK operation executor"
            : cell.variant() == LoadCase.Variant.PLATFORM
                ? "caller-owned: 8 platform threads, queue 128; closed by harness"
                : cell.submission() == LoadCase.Submission.SYNC
                    ? "calling thread (sync)"
                    : "SDK-owned virtual threads (async calls)");
    if (mode.equals("pilot") || mode.startsWith("diagnostic-long")) {
      result.put(
          "scheduler",
          Map.of(
              "availableProcessors", Runtime.getRuntime().availableProcessors(),
              "parallelismOverride",
                  System.getProperty("jdk.virtualThreadScheduler.parallelism", "unset"),
              "maxPoolSizeOverride",
                  System.getProperty("jdk.virtualThreadScheduler.maxPoolSize", "unset")));
    }
    return result;
  }

  private static void runCohorts(
      Map<String, Object> result, LoadCase cell, Duration warmup, Duration measurement)
      throws IOException, InterruptedException {
    if (cell.variant() == LoadCase.Variant.IMMEDIATE) {
      try (ImmediateSession session = new ImmediateSession(cell)) {
        result.put("warmup", session.cohort(warmup));
        validate(Json.toTree(result.get("warmup")), cell);
        result.put("measured", session.cohort(measurement));
        validate(Json.toTree(result.get("measured")), cell);
      }
    } else {
      try (LoadSession session = new LoadSession(cell)) {
        result.put("warmup", session.cohort(warmup));
        validate(Json.toTree(result.get("warmup")), cell);
        result.put("measured", session.cohort(measurement));
        validate(Json.toTree(result.get("measured")), cell);
      }
    }
  }

  private static void finishRecording(
      RestrictedRecording recording, Path output, Map<String, Object> result) throws IOException {
    var measured = Json.toTree(result.get("measured"));
    var diagnosticResult =
        recording.finish(
            output.resolve("diagnostics.jfr"),
            List.of(),
            new RestrictedRecording.Window(
                Instant.parse(measured.path("windowStart").asText()),
                Instant.parse(measured.path("windowEnd").asText())));
    result.put("diagnostics", diagnosticResult);
    if (Boolean.TRUE.equals(diagnosticResult.get("nearRetentionLimit"))
        || ((Number) diagnosticResult.get("recordingDataLossBytes")).longValue() != 0) {
      throw new IllegalStateException("Diagnostic recording lost data or approached retention cap");
    }
  }

  /** Diagnostic and baseline trials share ten seconds of warm-up before analysis. */
  public static Duration warmup(String mode) {
    return Duration.ofMillis(mode.equals("smoke") ? 100 : 10000);
  }

  /** Shared launch metadata and child timing, with no duplicated duration constants. */
  public static Duration measurement(String mode) {
    return Duration.ofMillis(
        mode.equals("baseline") || mode.equals("pilot") || mode.startsWith("diagnostic-long")
            ? 30000
            : mode.equals("smoke") ? 300 : 3000);
  }

  /** Accounting failures are hard errors, not noisy performance thresholds. */
  public static void validate(JsonNode result, LoadCase cell) {
    long admitted = result.path("admitted").asLong();
    long completed = result.path("completed").asLong();
    long inWindow = result.path("completedInWindow").asLong();
    if (inWindow < 0
        || inWindow > completed
        || inWindow + result.path("drainCompletions").asLong() != completed
        || admitted != result.path("offered").asLong()) {
      throw new IllegalStateException("Invalid cohort totals");
    }
    long totalOutcomes = 0;
    for (LoadAccounting.Outcome outcome : LoadAccounting.Outcome.values()) {
      long count = result.path("outcomes").path(outcome.name()).asLong();
      JsonNode histogram = result.path("latencyByOutcome").path(outcome.name());
      if (count < 0
          || count != histogram.path("recorded").asLong() + histogram.path("overflow").asLong()) {
        throw new IllegalStateException("Outcome/latency count mismatch");
      }
      totalOutcomes += count;
    }
    if (totalOutcomes != completed) {
      throw new IllegalStateException("Outcome total mismatch");
    }
    String expected =
        cell.status() == 200 ? "SUCCESS" : cell.status() < 500 ? "HTTP4XX" : "HTTP5XX";
    require(admitted > 0, "No requests admitted");
    require(admitted == completed, "Admitted/completed count mismatch");
    require(
        admitted == result.path("outcomes").path(expected).asLong(),
        "Unexpected outcome mix; expected " + expected);
    require(
        admitted == result.path("httpAttempts").asLong(), "Admission/HTTP attempt count mismatch");
    require(!result.path("forcedCleanup").asBoolean(), "Forced cleanup was required");
    require(result.path("unfinished").asLong() == 0, "Unfinished calls remain");
    require(
        cell.variant() == LoadCase.Variant.IMMEDIATE || result.path("serverDrained").asBoolean(),
        "Server did not drain");
    require(result.path("observersDrained").asBoolean(), "Observers did not drain");
    require(
        result.path("peakInFlight").asInt() <= cell.concurrency(), "Concurrency limit exceeded");
    require(
        result.path("serverAfter").path("rejectedTasks").asLong() == 0, "Server rejected tasks");
    if (cell.variant() == LoadCase.Variant.IMMEDIATE
        && (!result.path("transportKind").asText().equals("immediate-in-memory")
            || result.has("serverAfter")
            || result.path("expectedRequestBytesPerAttempt").asLong() <= 0
            || result.path("requestBytes").asLong()
                != admitted * result.path("expectedRequestBytesPerAttempt").asLong())) {
      throw new IllegalStateException("Invalid synthetic transport accounting");
    }
    if (cell.observed()) {
      JsonNode observation = result.path("observation");
      if (observation.path("finished").asLong() != admitted
          || observation.path("attempts").asLong() != admitted
          || observation.path("observerFailures").asLong() != 0
          || (cell.offsets() && !observation.path("correlation").path("complete").asBoolean())) {
        throw new IllegalStateException(
            "Observer accounting incomplete: admitted="
                + admitted
                + ", finished="
                + observation.path("finished")
                + ", attempts="
                + observation.path("attempts")
                + ", failures="
                + observation.path("observerFailures")
                + ", lastFailure="
                + observation.path("lastFailureClass")
                + ", correlation="
                + observation.path("correlation").path("complete")
                + ", overflow="
                + observation.path("correlation").path("overflow")
                + ", unmatched="
                + observation.path("correlation").path("unmatched"));
      }
    }
  }

  private static void require(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalStateException(reason + "; inspect results.json");
    }
  }
}
