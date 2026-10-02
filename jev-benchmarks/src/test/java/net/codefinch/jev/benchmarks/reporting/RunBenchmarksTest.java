package net.codefinch.jev.benchmarks.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.runner.BenchmarkList;

class RunBenchmarksTest {
  @Test
  void processorRegistersAllBenchmarkMethodsWithoutExecutingThem() throws Exception {
    try (var in = getClass().getResourceAsStream("/META-INF/BenchmarkList")) {
      assertThat(in).isNotNull();
      var entries = BenchmarkList.readBenchmarkList(in);
      Set<String> generated = new HashSet<>();
      Set<String> reflected = new HashSet<>();
      for (var entry : entries) {
        generated.add(entry.getUsername());
        var type = Class.forName(entry.getUserClassQName());
        for (var method : type.getMethods()) {
          if (method.isAnnotationPresent(Benchmark.class)) {
            reflected.add(type.getName() + "." + method.getName());
          }
        }
        for (var field : type.getFields()) {
          var param = field.getAnnotation(Param.class);
          if (param != null) {
            assertThat(entry.getParams().get().get(field.getName())).containsExactly(param.value());
          }
        }
      }
      assertThat(generated).isNotEmpty().containsExactlyInAnyOrderElementsOf(reflected);
    }
    try (var in = getClass().getResourceAsStream("/META-INF/CompilerHints")) {
      assertThat(in).isNotNull();
      assertThat(in.readAllBytes()).isNotEmpty();
    }
  }

  @Test
  void shadedMetadataMayContainBlankSeparatorLines() throws Exception {
    try (var in = getClass().getResourceAsStream("/META-INF/BenchmarkList")) {
      assertThat(in).isNotNull();
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      var settings = RunBenchmarks.Settings.parse("smoke", "timing", "all");
      assertThat(
              BenchmarkResults.expectedCells(
                  settings, BenchmarkList.fromString("\n" + text + "\n\n")))
          .containsExactlyInAnyOrderElementsOf(BenchmarkResults.expectedCells(settings));
    }
  }

  @Test
  void groupSelectionsMatchDeclaredParameters() {
    var all =
        BenchmarkResults.expectedCells(RunBenchmarks.Settings.parse("smoke", "timing", "all"));
    var core =
        BenchmarkResults.expectedCells(RunBenchmarks.Settings.parse("smoke", "timing", "core"));
    assertThat(core)
        .containsExactlyInAnyOrderElementsOf(
            all.stream()
                .filter(
                    cell ->
                        !cell.contains("ThresholdBenchmarks")
                            && !cell.contains("CompositeBenchmarks"))
                .toList());
    for (String group : Arrays.asList("ticket", "sizes", "structure")) {
      var settings = RunBenchmarks.Settings.parse("smoke", "timing", group);
      var selected = BenchmarkResults.expectedCells(settings);
      assertThat(selected).isNotEmpty().allMatch(all::contains);
      assertThat(selected).allMatch(cell -> settings.scenarios().stream().anyMatch(cell::contains));
      assertThat(RunBenchmarks.command(Path.of("benchmarks.jar"), Path.of("results"), settings))
          .contains("scenario=" + String.join(",", settings.scenarios()));
    }
  }

  @Test
  void existingOutputIsRefusedWithoutModifyingIt(@TempDir Path temp) throws Exception {
    Path output = Files.createDirectory(temp.resolve("existing"));
    Path sentinel = Files.writeString(output.resolve("manifest.json"), "original");
    assertThatThrownBy(
            () ->
                RunBenchmarks.run(
                    temp.resolve("unused.jar"),
                    output,
                    RunBenchmarks.Settings.parse("smoke", "timing", "ticket")))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(Files.readString(sentinel)).isEqualTo("original");
    try (var files = Files.list(output)) {
      assertThat(files.toList()).containsExactly(sentinel);
    }
  }

  @Test
  void failedChildLeavesFailedManifestAndNoSummary(@TempDir Path temp) throws Exception {
    Path jar = Files.writeString(temp.resolve("invalid.jar"), "not a jar");
    Path output = temp.resolve("failed");
    assertThatThrownBy(
            () ->
                RunBenchmarks.run(
                    jar, output, RunBenchmarks.Settings.parse("smoke", "timing", "ticket")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JMH failed");
    var manifest = Json.parse(Files.readString(output.resolve("manifest.json")));
    assertThat(manifest.path("status").asText()).isEqualTo("failed");
    assertThat(manifest.path("finishedAt").asText()).isNotBlank();
    assertThat(output.resolve("console.log")).exists();
    assertThat(output.resolve("summary.md")).doesNotExist();
  }

  @Test
  void controlledForkDoesNotInheritCredentialsOrJvmInjectionOptions() {
    var env =
        RunBenchmarks.cleanEnvironment(
            Map.of(
                "PATH",
                "/bin",
                "TYPESAFE_API_KEY",
                "sentinel",
                "JAVA_TOOL_OPTIONS",
                "-Dsecret=sentinel",
                "JDK_JAVA_OPTIONS",
                "-javaagent:sentinel",
                "_JAVA_OPTIONS",
                "-Xmx16m",
                "GH_TOKEN",
                "sentinel"));
    assertThat(env).containsOnlyKeys("PATH", "LANG");
    var command =
        RunBenchmarks.command(
            Path.of("benchmarks.jar"),
            Path.of("results"),
            RunBenchmarks.Settings.parse("smoke", "gc", "ticket"));
    assertThat(command).contains("-foe", "true", "-prof", "gc", "scenario=ticket", "100ms");
    assertThat(command).noneMatch(s -> s.contains("javaagent") || s.contains("sentinel"));
    assertThatThrownBy(() -> RunBenchmarks.Settings.parse("typo", "gc", "all"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void incompleteOrInvalidResultsCannotBecomeSuccessfulReports() throws Exception {
    var settings = RunBenchmarks.Settings.parse("smoke", "timing", "ticket");
    assertThatThrownBy(() -> BenchmarkResults.validate(Json.parse("[]"), settings))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> BenchmarkResults.validate(Json.parse("[{},{}]"), settings))
        .isInstanceOf(IllegalStateException.class);
    var valid =
        Json.parse(
            """
            [{"benchmark":"net.codefinch.jev.benchmarks.components.WireBenchmarks.parse",
              "params":{"scenario":"ticket"},
              "primaryMetric":{"score":12.5,"scoreUnit":"ns/op","scoreError":"NaN"}},
             {"benchmark":"net.codefinch.jev.benchmarks.components.WireBenchmarks.serialize",
              "params":{"scenario":"ticket"},
              "primaryMetric":{"score":22.5,"scoreUnit":"ns/op","scoreError":"NaN"}}]
            """);
    BenchmarkResults.validate(valid, settings);
    assertThat(BenchmarkResults.summary(valid, settings)).contains("Smoke run", "12.500", "n/a");
    var duplicate = Json.parse("[" + valid.get(0) + "," + valid.get(0) + "]");
    assertThatThrownBy(() -> BenchmarkResults.validate(duplicate, settings))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                BenchmarkResults.validate(
                    valid, RunBenchmarks.Settings.parse("smoke", "gc", "ticket")))
        .isInstanceOf(IllegalStateException.class);
  }
}
