package net.codefinch.jev.benchmarks.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.codefinch.jev.benchmarks.reporting.RunBenchmarks.Settings;
import net.codefinch.jev.internal.Json;
import org.openjdk.jmh.runner.BenchmarkList;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.VerboseMode;

/** Validates the complete JMH result matrix and renders a compact summary. */
final class BenchmarkResults {
  private BenchmarkResults() {}

  static void validate(JsonNode results, Settings settings) {
    Set<String> remaining = expectedCells(settings);
    if (!results.isArray() || results.size() != remaining.size()) {
      throw new IllegalStateException(
          "Expected " + remaining.size() + " completed benchmark cells");
    }
    for (JsonNode row : results) {
      Map<String, String> params = new TreeMap<>();
      row.path("params").properties().forEach(p -> params.put(p.getKey(), p.getValue().asText()));
      String identity = row.path("benchmark").asText() + "#" + Json.write(Json.toTree(params));
      if (!remaining.remove(identity)) {
        throw new IllegalStateException("Unexpected or duplicate benchmark cell: " + identity);
      }
      JsonNode metric = row.path("primaryMetric");
      if (!metric.path("score").isNumber()
          || !Double.isFinite(metric.path("score").doubleValue())
          || !"ns/op".equals(metric.path("scoreUnit").asText())) {
        throw new IllegalStateException("Missing or invalid primary result");
      }
      if (settings.gc()) {
        JsonNode allocation = row.path("secondaryMetrics").path("gc.alloc.rate.norm").path("score");
        if (!allocation.isNumber() || !Double.isFinite(allocation.doubleValue())) {
          throw new IllegalStateException("Allocation profiler result is missing or invalid");
        }
      }
    }
  }

  static Set<String> expectedCells(Settings settings) {
    return expectedCells(settings, BenchmarkList.defaultList());
  }

  static Set<String> expectedCells(Settings settings, BenchmarkList list) {
    Set<String> cells = new LinkedHashSet<>();
    var output = OutputFormatFactory.createFormatInstance(System.out, VerboseMode.SILENT);
    for (var entry : list.getAll(output, List.of())) {
      if (!entry.getUsername().matches(settings.include())) {
        continue;
      }
      List<Map<String, String>> combinations = new ArrayList<>();
      combinations.add(new TreeMap<>());
      for (var param : entry.getParams().orElse(Map.of()).entrySet()) {
        List<String> values = List.of(param.getValue());
        if (param.getKey().equals("scenario") && !settings.scenarios().isEmpty()) {
          if (!values.containsAll(settings.scenarios())) {
            throw new IllegalStateException("Group selects an undeclared scenario");
          }
          values = settings.scenarios();
        }
        List<Map<String, String>> expanded = new ArrayList<>();
        for (var combination : combinations) {
          for (String value : values) {
            Map<String, String> next = new TreeMap<>(combination);
            next.put(param.getKey(), value);
            expanded.add(next);
          }
        }
        combinations = expanded;
      }
      for (var combination : combinations) {
        cells.add(entry.getUsername() + "#" + Json.write(Json.toTree(combination)));
      }
    }
    if (cells.isEmpty()) {
      throw new IllegalStateException("Empty benchmark selection");
    }
    return cells;
  }

  static String summary(JsonNode results, Settings settings) {
    StringBuilder out = new StringBuilder("# Component measurements\n\n");
    out.append(
        settings.smoke()
            ? "Smoke run: verifies execution, not a performance baseline.\n\n"
            : "Initial measurements; not a regression gate or a five-run noise estimate.\n\n");
    out.append(
            "See manifest.json for runtime/fixture identity and results.json for raw"
                + " iterations.\n\n")
        .append("| Benchmark | Parameters | ns/op | JMH error | B/op (GC pass) |\n")
        .append("|---|---|---:|---:|---:|\n");
    for (JsonNode row : results) {
      JsonNode metric = row.path("primaryMetric");
      out.append("| ")
          .append(
              row.path("benchmark")
                  .asText()
                  .replace("net.codefinch.jev.benchmarks.components.", ""))
          .append(" | ")
          .append(row.path("params"))
          .append(" | ")
          .append(number(metric.path("score")))
          .append(" | ")
          .append(number(metric.path("scoreError")))
          .append(" | ")
          .append(number(row.path("secondaryMetrics").path("gc.alloc.rate.norm").path("score")))
          .append(" |\n");
    }
    return out.toString();
  }

  private static String number(JsonNode value) {
    return value.isNumber() && Double.isFinite(value.doubleValue())
        ? String.format(Locale.ROOT, "%.3f", value.doubleValue())
        : "n/a";
  }
}
