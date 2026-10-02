package net.codefinch.jev.benchmarks.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import net.codefinch.jev.internal.Json;

/** Synthetic child used only for explicit local recording-content checks, never ordinary verify. */
public final class RecordingProbe {
  private static volatile Object retained;

  private RecordingProbe() {}

  /** Receives only an output directory and harmless sentinel argument. */
  public static void main(String[] args)
      throws java.io.IOException, InterruptedException, java.text.ParseException {
    if (args.length != 3 || !List.of("short", "long").contains(args[2])) {
      throw new IllegalArgumentException("Expected output, sentinel and short|long profile");
    }
    List<String> sentinels =
        List.of(
            System.getenv("JEV_BENCH_SENTINEL_ENV"),
            System.getProperty("jev.bench.sentinel"),
            args[1]);
    if (sentinels.stream().anyMatch(s -> !s.startsWith("BENCH_SENTINEL_"))) {
      throw new IllegalArgumentException("Missing synthetic sentinels");
    }
    Path output = Path.of(args[0]);
    try (RestrictedRecording recording = new RestrictedRecording(args[2].equals("long"))) {
      for (int i = 0; i < 2000; i++) {
        retained = new byte[65536];
      }
      System.gc();
      Thread.sleep(100);
      var result = recording.finish(output.resolve("sentinel-check.jfr"), sentinels);
      var counts = Json.toTree(result).path("eventCounts");
      if (counts.path("jdk.GarbageCollection").asLong() == 0
          || counts.path("jdk.ObjectAllocationSample").asLong() == 0) {
        throw new IllegalStateException("Positive recording lacks intended diagnostic events");
      }
      Instant after = Instant.now().plusSeconds(3600);
      var excluded =
          RestrictedRecording.inspect(
              output.resolve("sentinel-check.jfr"),
              sentinels,
              new RestrictedRecording.Window(after, after.plusSeconds(1)));
      if (!Json.toTree(excluded).path("eventCounts").isEmpty()
          || Json.toTree(excluded).path("recordingEventCounts").isEmpty()) {
        throw new IllegalStateException("Event-start window filter did not exclude past events");
      }
      result.put("emptyWindowCheck", true);
      Path negative = output.resolve("negative-control.jfr");
      boolean rejected = false;
      try {
        try (Recording control = new Recording()) {
          control.enable(ProbeEvent.class);
          control.start();
          ProbeEvent event = new ProbeEvent();
          event.value = sentinels.get(0);
          event.commit();
          control.stop();
          control.dump(negative);
        }
        try {
          RestrictedRecording.inspect(
              negative, sentinels, new RestrictedRecording.Window(after, after.plusSeconds(1)));
        } catch (IllegalStateException expected) {
          rejected = true;
        }
      } finally {
        Files.deleteIfExists(negative);
      }
      if (!rejected) {
        throw new IllegalStateException("Sentinel negative control was not detected");
      }
      result.put(
          "positiveSentinelCheck",
          "passed: environment, property and argument absent from every recorded field");
      result.put("negativeControlRejected", true);
      result.put("javaVersion", System.getProperty("java.version"));
      result.put("javaVendor", System.getProperty("java.vendor"));
      Files.writeString(
          output.resolve("sentinel-check.json"), Json.toTree(result).toPrettyString() + "\n");
    }
  }

  @Name("net.codefinch.jev.benchmark.SentinelNegativeControl")
  private static final class ProbeEvent extends Event {
    public String value;
  }
}
