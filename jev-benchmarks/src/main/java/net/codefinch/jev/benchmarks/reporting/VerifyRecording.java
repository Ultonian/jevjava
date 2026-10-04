package net.codefinch.jev.benchmarks.reporting;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.benchmarks.config.JvmSettings;
import net.codefinch.jev.benchmarks.diagnostics.RecordingProbe;

/** Explicit two-sided artifact-content check for the current JDK and packaged configuration. */
public final class VerifyRecording {
  private VerifyRecording() {}

  /** Usage: {@code VerifyRecording NEW_OUTPUT_DIRECTORY [short|long]}. */
  public static void main(String[] args)
      throws IOException, InterruptedException, URISyntaxException {
    if ((args.length != 1 && args.length != 2)
        || (args.length == 2 && !List.of("short", "long").contains(args[1]))) {
      throw new IllegalArgumentException("Expected new output directory and optional short|long");
    }
    Path jar =
        Path.of(VerifyRecording.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!Files.isRegularFile(jar)) {
      throw new IllegalStateException("Run the packaged JAR");
    }
    Path output = Path.of(args[0]).toAbsolutePath();
    Path parent = output.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.createDirectory(output);
    RunLoad.runChild(
        List.of(
            ChildJvm.executable(),
            JvmSettings.MIN_HEAP,
            JvmSettings.MAX_HEAP,
            JvmSettings.GC,
            "-Djev.bench.sentinel=BENCH_SENTINEL_PROPERTY_7C92",
            "-cp",
            jar.toString(),
            RecordingProbe.class.getName(),
            output.toString(),
            "BENCH_SENTINEL_ARGUMENT_7C92",
            args.length == 2 ? args[1] : "short"),
        output.resolve("console.log"),
        30,
        Map.of("JEV_BENCH_SENTINEL_ENV", "BENCH_SENTINEL_ENVIRONMENT_7C92"));
    System.out.println("Recording sentinel checks passed: " + output);
  }
}
