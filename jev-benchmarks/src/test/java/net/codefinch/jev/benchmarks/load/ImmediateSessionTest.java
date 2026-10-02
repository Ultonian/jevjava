package net.codefinch.jev.benchmarks.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.internal.Json;
import org.junit.jupiter.api.Test;

class ImmediateSessionTest {
  @Test
  void transportConsumesUtf8AndUsesActualBodyHandlerWithoutNetwork() {
    try (var client = new ImmediateHttpClient()) {
      String text = "refund € café";
      var request =
          HttpRequest.newBuilder(ImmediateHttpClient.ENDPOINT)
              .POST(HttpRequest.BodyPublishers.ofString(text))
              .build();
      var decoded = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
      assertThat(decoded)
          .isCompletedWithValueMatching(r -> r.body().equals(Payloads.response("ticket")));
      var bytes = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertThat(bytes.body())
          .isEqualTo(Payloads.response("ticket").getBytes(StandardCharsets.UTF_8));
      assertThat(bytes.request()).isSameAs(request);
      assertThat(client.attempts()).isEqualTo(2);
      assertThat(client.requestBytes())
          .isEqualTo(2L * text.getBytes(StandardCharsets.UTF_8).length);
      assertThatThrownBy(
              () ->
                  client.send(
                      HttpRequest.newBuilder(URI.create("https://example.com"))
                          .POST(HttpRequest.BodyPublishers.ofString(text))
                          .build(),
                      HttpResponse.BodyHandlers.ofString()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void realSdkParsesAnswersOnBothPathsAndClosesNormally() throws Exception {
    try (var transport = new ImmediateHttpClient();
        var client =
            JevClient.builder()
                .apiKey("benchmark-dummy")
                .baseUrl(ImmediateHttpClient.ENDPOINT.resolve("/"))
                .httpClient(transport)
                .defaultModel("jev-latest")
                .build()) {
      var request = Payloads.request(Payloads.content("ticket"), 3);
      var sync = client.systemOne(request);
      var async = client.systemOneAsync(request).get();
      assertThat(sync.answers().asMap()).hasSize(3);
      assertThat(async.answers().asMap()).isEqualTo(sync.answers().asMap());
      assertThat(transport.attempts()).isEqualTo(2);
    }
  }

  @Test
  void bothStylesDrainIndependentCohortsAndRejectCorruptByteCounts() throws Exception {
    for (var cell : LoadCase.selection("immediate")) {
      try (var session = new ImmediateSession(cell)) {
        for (int i = 0; i < 2; i++) {
          var result = Json.toTree(session.cohort(Duration.ofMillis(100)));
          LoadTrial.validate(result, cell);
          assertThat(result.has("serverAfter")).isFalse();
          assertThat(result.path("peakInFlight").asInt()).isBetween(1, 8);
          com.fasterxml.jackson.databind.node.ObjectNode bad = result.deepCopy();
          bad.put("requestBytes", result.path("requestBytes").asLong() - 1);
          assertThatThrownBy(() -> LoadTrial.validate(bad, cell))
              .isInstanceOf(IllegalStateException.class);
        }
      }
    }
  }
}
