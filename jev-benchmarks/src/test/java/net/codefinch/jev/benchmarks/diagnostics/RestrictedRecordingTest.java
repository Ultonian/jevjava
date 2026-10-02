package net.codefinch.jev.benchmarks.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RestrictedRecordingTest {
  @Test
  void longProfileOnlyChangesSamplingAndAddsDataLossDetection() throws Exception {
    var settings = new ArrayList<java.util.Map<String, String>>();
    for (String file : List.of("restricted.jfc", "restricted-long.jfc")) {
      try (var stream = getClass().getResourceAsStream("/diagnostics/" + file);
          var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        settings.add(new HashMap<>(jdk.jfr.Configuration.create(reader).getSettings()));
      }
    }
    var expected = settings.getFirst();
    assertThat(expected.get("jdk.ExecutionSample#period")).isEqualTo("20 ms");
    expected.put("jdk.ExecutionSample#period", "10 ms");
    expected.put("jdk.NativeMethodSample#period", "10 ms");
    expected.put("jdk.DataLoss#enabled", "true");
    assertThat(settings.getLast()).isEqualTo(expected);
  }

  @Test
  void innerSdkAndTransportFramesAreNotOverriddenByOuterCallers() {
    String parser = "net.codefinch.jev.internal.ResponseParser.parseSystemOne";
    String caller = "net.codefinch.jev.benchmarks.load.LoadSession.invoke";
    String transport = "jdk.internal.net.http.HttpClientImpl.sendAsync";
    assertThat(
            RestrictedRecording.allocationRole(
                List.of("com.fasterxml.jackson.databind.ObjectMapper.readTree", parser, caller),
                ""))
        .isEqualTo("sdk");
    assertThat(RestrictedRecording.allocationRole(List.of(transport, parser, caller), ""))
        .isEqualTo("transport");
    assertThat(RestrictedRecording.allocationRole(List.of(caller, parser), ""))
        .isEqualTo("caller-or-driver");
    assertThat(RestrictedRecording.allocationRole(List.of(parser), "benchmark-server-1"))
        .isEqualTo("sdk");
    assertThat(RestrictedRecording.allocationRole(List.of(), "benchmark-server-1"))
        .isEqualTo("server");
  }

  @Test
  void windowExcludesWarmupAndDrainAndIncludesItsStart() {
    Instant start = Instant.parse("2026-09-28T00:00:00Z");
    var window = new RestrictedRecording.Window(start, start.plusSeconds(3));
    assertThat(window.contains(start.minusNanos(1))).isFalse();
    assertThat(window.contains(start)).isTrue();
    assertThat(window.contains(start.plusSeconds(3).minusNanos(1))).isTrue();
    assertThat(window.contains(start.plusSeconds(3))).isFalse();
    assertThatThrownBy(() -> new RestrictedRecording.Window(start, start))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stackReservoirIsBoundedReproducibleAndRetainsLateEvents() {
    List<Object> first = new ArrayList<>();
    List<Object> second = new ArrayList<>();
    Random a = new Random(0);
    Random b = new Random(0);
    for (int i = 1; i <= 10000; i++) {
      RestrictedRecording.retainPin(first, i, i, a);
      RestrictedRecording.retainPin(second, i, i, b);
    }
    assertThat(first).hasSize(32).isEqualTo(second);
    assertThat(first.stream().mapToInt(value -> (Integer) value).max().orElseThrow())
        .isGreaterThan(5000);
  }
}
