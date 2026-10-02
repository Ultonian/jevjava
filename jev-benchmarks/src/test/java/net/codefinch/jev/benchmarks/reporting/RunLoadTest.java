package net.codefinch.jev.benchmarks.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.benchmarks.load.LoadCase;
import net.codefinch.jev.benchmarks.load.LoadTrial;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLoadTest {
  @Test
  void immediateSelectionIsBoundedAndReversible() {
    var forward = RunLoad.orderedCells("pilot", "immediate", "forward");
    assertThat(forward.stream().map(LoadCase::id))
        .containsExactly("sdk-sync-8-immediate", "sdk-async-8-immediate");
    assertThat(RunLoad.orderedCells("pilot", "immediate", "reverse"))
        .containsExactlyElementsOf(forward.reversed());
    assertThatThrownBy(() -> RunLoad.orderedCells("baseline", "immediate", "forward"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new LoadCase(LoadCase.Submission.SYNC, 1, LoadCase.Variant.IMMEDIATE, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void longDiagnosticsMatchDurationsAndCellsWithoutChangingHistoricalModes() {
    for (String mode : List.of("diagnostic-long", "diagnostic-long-control")) {
      assertThat(LoadTrial.warmup(mode)).hasSeconds(10);
      assertThat(LoadTrial.measurement(mode)).hasSeconds(30);
      assertThat(RunLoad.orderedCells(mode, "representative", "forward"))
          .isEqualTo(LoadCase.selection("representative"));
      assertThatThrownBy(() -> RunLoad.orderedCells(mode, "matrix", "forward"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> RunLoad.orderedCells(mode, "representative", "reverse"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(LoadTrial.measurement("diagnostic")).hasSeconds(3);
    assertThat(LoadTrial.measurement("diagnostic-control")).hasSeconds(3);
  }

  @Test
  void executorPilotUsesReversedMatchedCellsAndFullWindows(@TempDir Path temp) throws Exception {
    var forward = RunLoad.orderedCells("pilot", "async-executor", "forward");
    assertThat(forward.stream().map(LoadCase::id))
        .containsExactly("sdk-async-8-base", "sdk-async-8-platform", "sdk-sync-8-base");
    assertThat(RunLoad.orderedCells("pilot", "async-executor", "reverse"))
        .containsExactlyElementsOf(forward.reversed());
    assertThat(LoadTrial.warmup("pilot")).hasSeconds(10);
    assertThat(LoadTrial.measurement("pilot")).hasSeconds(30);
    Path failed = temp.resolve("failed");
    Path jar = Files.writeString(temp.resolve("invalid.jar"), "not a jar");
    assertThatThrownBy(() -> RunLoad.run(jar, failed, "pilot", "async-executor", "reverse"))
        .isInstanceOf(IllegalStateException.class);
    var settings = Json.parse(Files.readString(failed.resolve("manifest.json"))).path("settings");
    assertThat(settings.path("forks").asInt()).isEqualTo(1);
    assertThat(settings.path("measurementMillis").asInt()).isEqualTo(30000);
    assertThat(settings.path("cells")).isEqualTo(Json.toTree(forward.reversed()));
    assertThat(settings.path("order").asText()).isEqualTo("reverse");
    for (var combination :
        List.of(
            List.of("pilot", "representative", "forward"),
            List.of("baseline", "async-executor", "forward"),
            List.of("smoke", "representative", "reverse"),
            List.of("pilot", "async-executor", "unknown"))) {
      Path invalid = temp.resolve("invalid");
      assertThatThrownBy(
              () ->
                  RunLoad.run(
                      jar, invalid, combination.get(0), combination.get(1), combination.get(2)))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(invalid).doesNotExist();
    }
  }

  @Test
  void selectionsHaveDistinctIdentitiesAndMatchedControls() {
    for (String group : List.of("representative", "core", "matrix")) {
      var cells = LoadCase.selection(group);
      assertThat(cells.stream().map(LoadCase::id)).doesNotHaveDuplicates();
      for (var cell : cells) {
        if (!cell.control()) {
          assertThat(cells).contains(cell.controlCase());
        }
      }
    }
    assertThat(LoadCase.selection("offsets")).allMatch(LoadCase::offsets);
  }

  @Test
  void refusesExistingOutputAndPreservesFailedChildEvidence(@TempDir Path temp) throws Exception {
    Path existing = Files.createDirectory(temp.resolve("existing"));
    Path sentinel = Files.writeString(existing.resolve("manifest.json"), "original");
    Path jar = Files.writeString(temp.resolve("invalid.jar"), "not a jar");
    assertThatThrownBy(() -> RunLoad.run(jar, existing, "smoke", "representative"))
        .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    assertThat(Files.readString(sentinel)).isEqualTo("original");
    Path failed = temp.resolve("failed");
    assertThatThrownBy(() -> RunLoad.run(jar, failed, "smoke", "representative"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Trial failed");
    assertThat(
            Json.parse(Files.readString(failed.resolve("manifest.json"))).path("status").asText())
        .isEqualTo("failed");
    assertThat(failed.resolve("summary.md")).doesNotExist();
    assertThatThrownBy(() -> RunLoad.run(jar, temp.resolve("invalid"), "baseline", "offsets"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(temp.resolve("invalid")).doesNotExist();
  }

  @Test
  void watchdogTerminatesTheChild(@TempDir Path temp) throws Exception {
    Path pid = temp.resolve("pid");
    var command =
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            SleepingChild.class.getName(),
            pid.toString());
    assertThatThrownBy(() -> RunLoad.runChild(command, temp.resolve("console.log"), 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("watchdog");
    var child = ProcessHandle.of(Long.parseLong(Files.readString(pid)));
    if (child.isPresent()) {
      child.get().onExit().get(5, TimeUnit.SECONDS);
      assertThat(child.get().isAlive()).isFalse();
    }
  }

  public static final class SleepingChild {
    private SleepingChild() {}

    /** Minimal independent process for the launcher's watchdog test. */
    public static void main(String[] args) throws java.io.IOException, InterruptedException {
      Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
      Thread.sleep(20_000);
    }
  }
}
