package net.codefinch.jev.benchmarks.diagnostics;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;
import net.codefinch.jev.benchmarks.fixtures.Payloads;

/** Explicitly allowlisted recording and streaming analysis; separate from throughput baselines. */
public final class RestrictedRecording implements AutoCloseable {
  private static final long RETENTION_LIMIT_BYTES = 64L * 1024 * 1024;
  private static final long RETENTION_WARNING_BYTES = 56L * 1024 * 1024;
  private static final int PIN_STACK_LIMIT = 32;
  private final Recording recording;
  private final Map<String, String> settings;
  private final String configHash;

  /**
   * Disables every event before applying the checked-in allowlist. The long-window profile samples
   * execution at 10 ms and enables data-loss detection.
   */
  public RestrictedRecording(boolean longWindow) throws IOException, ParseException {
    final byte[] bytes;
    String resource = longWindow ? "restricted-long.jfc" : "restricted.jfc";
    try (var in = RestrictedRecording.class.getResourceAsStream("/diagnostics/" + resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing restricted JFR configuration");
      }
      bytes = in.readAllBytes();
    }
    configHash = Payloads.hash(bytes);
    Configuration config =
        Configuration.create(
            new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
    settings = new HashMap<>();
    var available = new HashSet<String>();
    for (var event : FlightRecorder.getFlightRecorder().getEventTypes()) {
      available.add(event.getName());
      settings.put(event.getName() + "#enabled", "false");
    }
    for (var setting : config.getSettings().entrySet()) {
      if (setting.getKey().endsWith("#enabled")
          && setting.getValue().equals("true")
          && !available.contains(setting.getKey().split("#")[0])) {
        throw new IllegalStateException(
            "Required diagnostic event unavailable: " + setting.getKey());
      }
    }
    settings.putAll(config.getSettings());
    recording = new Recording(settings);
    recording.setMaxSize(RETENTION_LIMIT_BYTES);
    recording.start();
  }

  /** Event-start selection uses a half-open interval; result drain is outside the window. */
  public record Window(Instant start, Instant end) {
    /** Rejects empty or reversed windows. */
    public Window {
      if (!start.isBefore(end)) {
        throw new IllegalArgumentException("Invalid diagnostic window");
      }
    }

    boolean contains(Instant time) {
      return !time.isBefore(start) && time.isBefore(end);
    }
  }

  /** Stops and saves the entire recording, used by the explicit sentinel probe. */
  public Map<String, Object> finish(Path file, List<String> sentinels) throws IOException {
    return finish(file, sentinels, null);
  }

  /** Checks the entire recording but attributes only events starting in the selected window. */
  public Map<String, Object> finish(Path file, List<String> sentinels, Window window)
      throws IOException {
    recording.stop();
    recording.dump(file);
    Map<String, Object> result = inspect(file, sentinels, window);
    @SuppressWarnings("unchecked")
    Map<String, Long> counts = (Map<String, Long>) result.get("recordingEventCounts");
    if (counts.keySet().stream()
        .anyMatch(name -> !"true".equals(settings.get(name + "#enabled")))) {
      throw new IllegalStateException("Recording contains an event outside the allowlist");
    }
    result.put("configurationSha256", configHash);
    result.put("settings", settings);
    result.put("recordingBytes", Files.size(file));
    result.put("recordingSha256", Payloads.hash(Files.readAllBytes(file)));
    result.put("retentionLimitBytes", RETENTION_LIMIT_BYTES);
    result.put("nearRetentionLimit", recording.getSize() >= RETENTION_WARNING_BYTES);
    result.put("recordingScope", "entire trial; privacy and allowlist checks cover every event");
    return result;
  }

  /** Scans all fields for sentinels before applying the optional event-start filter. */
  public static Map<String, Object> inspect(Path file, List<String> sentinels, Window window)
      throws IOException {
    Analysis analysis = new Analysis();
    try (RecordingFile input = new RecordingFile(file)) {
      while (input.hasMoreEvents()) {
        analysis.accept(input.readEvent(), sentinels, window);
      }
    }
    return analysis.snapshot(window);
  }

  private static final class Analysis {
    private final Map<String, Long> recordingCounts = new TreeMap<>();
    private final Map<String, Long> counts = new TreeMap<>();
    private final Map<String, Long> weights = new TreeMap<>();
    private final Map<String, Long> allocationSamples = new TreeMap<>();
    private final List<Object> pinStacks = new ArrayList<>();
    private final Map<String, Long> pinReasons = new TreeMap<>();
    private final Map<String, Long> pinOperations = new TreeMap<>();
    private final SampleSummary samples = new SampleSummary(1024);
    private long lostBytes = 0;
    private long pinnedNanos = 0;
    private final Random reservoir = new Random(0);

    void accept(RecordedEvent event, List<String> sentinels, Window window) {
      String name = event.getEventType().getName();
      if (!sentinels.isEmpty()) {
        scan(event, sentinels, new IdentityHashMap<>());
      }
      recordingCounts.merge(name, 1L, Long::sum);
      if (name.equals("jdk.DataLoss")) {
        lostBytes += event.getLong("amount");
      }
      if (window != null && !window.contains(event.getStartTime())) {
        return;
      }
      counts.merge(name, 1L, Long::sum);
      if (name.equals("jdk.ExecutionSample") || name.equals("jdk.NativeMethodSample")) {
        var thread = event.getThread("sampledThread");
        samples.add(
            name,
            frames(event),
            thread == null ? "" : String.valueOf(thread.getJavaName()),
            thread == null ? "unknown" : thread.isVirtual() ? "virtual" : "platform",
            event.getStackTrace() != null && event.getStackTrace().isTruncated());
      }
      if (name.equals("jdk.ObjectAllocationSample")) {
        String role = allocationRole(event);
        weights.merge(role, event.getLong("weight"), Long::sum);
        allocationSamples.merge(role, 1L, Long::sum);
      }
      if (name.equals("jdk.VirtualThreadPinned")) {
        pinnedNanos += event.getDuration().toNanos();
        pinReasons.merge(pinField(event, "pinnedReason"), 1L, Long::sum);
        pinOperations.merge(pinField(event, "blockingOperation"), 1L, Long::sum);
        retainPin(
            pinStacks,
            Map.of(
                "durationNanos",
                event.getDuration().toNanos(),
                "start",
                event.getStartTime().toString(),
                "frames",
                frames(event)),
            counts.get(name),
            reservoir);
      }
    }

    Map<String, Object> snapshot(Window window) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("recordingEventCounts", recordingCounts);
      result.put("eventCounts", counts);
      result.put("recordingDataLossBytes", lostBytes);
      result.put("executionSamples", samples.snapshot());
      result.put(
          "scope",
          window == null
              ? "entire recording; combined process"
              : "measured window; combined process; event start in [start,end), full event"
                  + " duration");
      if (window != null) {
        result.put("windowStart", window.start().toString());
        result.put("windowEnd", window.end().toString());
      }
      result.put("allocationSampleCountsByRole", allocationSamples);
      result.put("allocationSampleWeightsByRole", weights);
      result.put(
          "attribution",
          "innermost matching frame among first 64; SDK frames precede outer callers naturally;"
              + " server thread name is a fallback only");
      result.put(
          "allocationInterpretation",
          "sample weights estimate pressure; sample count is not bytes and weights are not an"
              + " allocation census");
      result.put("pinningThreshold", "1 ms");
      result.put("pinnedNanos", pinnedNanos);
      result.put("pinReasons", pinReasons);
      result.put("pinBlockingOperations", pinOperations);
      result.put("pinnedStacks", pinStacks);
      result.put(
          "pinStackSampling",
          "uniform reservoir, capacity 32, java.util.Random seed 0, recording traversal order");
      result.put(
          "pinningInterpretation",
          "zero events means none observed under these settings, not proof of no pinning");
      return result;
    }
  }

  private static String pinField(RecordedEvent event, String field) {
    return event.hasField(field)
        ? String.valueOf(event.getString(field))
        : "unavailable in this JDK event";
  }

  static void retainPin(List<Object> samples, Object sample, long seen, Random random) {
    long index = seen <= PIN_STACK_LIMIT ? seen - 1 : random.nextLong(seen);
    if (index < PIN_STACK_LIMIT) {
      if (samples.size() < PIN_STACK_LIMIT) {
        samples.add(sample);
      } else {
        samples.set((int) index, sample);
      }
    }
  }

  private static String allocationRole(RecordedEvent event) {
    return allocationRole(
        frames(event),
        event.getThread() == null ? "" : String.valueOf(event.getThread().getJavaName()));
  }

  static String allocationRole(List<String> stack, String threadName) {
    for (String frame : stack) {
      if (frame.startsWith("net.codefinch.jev.benchmarks.server.")) {
        return "server";
      }
      if (frame.contains("CallExecution.lambda$observe")
          || frame.startsWith("net.codefinch.jev.benchmarks.load.ObservedCalls.on")
          || frame.startsWith("net.codefinch.jev.micrometer.")) {
        return "observer-delivery";
      }
      if (frame.contains("CallExecution.lambda$publishResult$")) {
        return "result-delivery";
      }
      if (frame.startsWith("jdk.internal.net.http.") || frame.startsWith("java.net.http.")) {
        return "transport";
      }
      if (frame.startsWith("net.codefinch.jev.benchmarks.load.LoadDriver.")
          || frame.startsWith("net.codefinch.jev.benchmarks.load.LoadSession.")) {
        return "caller-or-driver";
      }
      if (frame.contains("CallExecution.lambda$runAsync$")) {
        return "sdk-async-operation";
      }
      if (frame.startsWith("net.codefinch.jev.")
          && !frame.startsWith("net.codefinch.jev.benchmarks.")) {
        return "sdk";
      }
    }
    return threadName.startsWith("benchmark-server-") ? "server" : "unclassified";
  }

  private static List<String> frames(RecordedEvent event) {
    if (event.getStackTrace() == null) {
      return List.of();
    }
    return event.getStackTrace().getFrames().stream()
        .limit(64)
        .map(frame -> frame.getMethod().getType().getName() + "." + frame.getMethod().getName())
        .toList();
  }

  private static void scan(
      Object value, List<String> sentinels, IdentityHashMap<Object, Boolean> seen) {
    if (value == null || seen.put(value, Boolean.TRUE) != null) {
      return;
    }
    if (value instanceof String text) {
      if (sentinels.stream().anyMatch(text::contains)) {
        throw new IllegalStateException("Sentinel leaked into diagnostic recording");
      }
    } else if (value instanceof RecordedObject object) {
      for (var field : object.getFields()) {
        scan(object.getValue(field.getName()), sentinels, seen);
      }
    } else if (value instanceof Object[] array) {
      for (Object entry : array) {
        scan(entry, sentinels, seen);
      }
    }
  }

  /** Releases JFR resources even when a workload fails. */
  @Override
  public void close() {
    recording.close();
  }
}
