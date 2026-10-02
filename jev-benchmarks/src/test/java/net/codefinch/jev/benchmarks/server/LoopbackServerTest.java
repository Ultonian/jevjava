package net.codefinch.jev.benchmarks.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoopbackServerTest {
  @Test
  void refusesUnmanagedEndpointsWithoutResolvingOrContactingThem() {
    URI managed = URI.create("http://127.0.0.1:1234");
    for (String endpoint :
        java.util.List.of(
            "https://example.invalid",
            "http://localhost:1234",
            "http://127.0.0.1:1235",
            "http://user@127.0.0.1:1234",
            "http://127.0.0.1:1234?override=true")) {
      assertThatThrownBy(() -> LoopbackServer.requireManagedEndpoint(URI.create(endpoint), managed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    LoopbackServer.requireManagedEndpoint(managed, managed);
  }

  @Test
  void servesRepeatableBoundedPatternsAndRejectsOversizedBodies() throws Exception {
    try (var server = new LoopbackServer("ticket", 2, 0, 0, 200);
        var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      var request =
          HttpRequest.newBuilder(server.uri().resolve("/v1/systemone"))
              .timeout(Duration.ofSeconds(3))
              .header("X-Benchmark-Call", "42")
              .header("X-Benchmark-Pattern", "503,200")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(503);
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.headers().firstValue("x-typesafe-request-id")).contains("42");
      var large =
          HttpRequest.newBuilder(server.uri().resolve("/v1/systemone"))
              .timeout(Duration.ofSeconds(3))
              .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1024 * 1024 + 1]))
              .build();
      assertThat(client.send(large, HttpResponse.BodyHandlers.discarding()).statusCode())
          .isEqualTo(413);
      assertThat(server.awaitIdle(Duration.ofSeconds(3))).isTrue();
      assertThat(server.snapshot().get("requests")).isEqualTo(3L);
      var counts =
          net.codefinch.jev.internal.Json.toTree(server.snapshot()).path("headersSentByStatus");
      assertThat(counts.path("200").asLong()).isEqualTo(1);
      assertThat(counts.path("503").asLong()).isEqualTo(1);
      assertThat(counts.path("413").asLong()).isEqualTo(1);
    }
  }

  @Test
  void closeReleasesTheListeningSocketAndAllWorkers() throws Exception {
    var server = new LoopbackServer("ticket", 2, 0, 0, 200);
    URI uri = server.uri();
    server.close();
    try (var client = HttpClient.newHttpClient()) {
      assertThatThrownBy(
              () ->
                  client.send(
                      HttpRequest.newBuilder(uri.resolve("/v1/models"))
                          .timeout(Duration.ofSeconds(1))
                          .build(),
                      HttpResponse.BodyHandlers.discarding()))
          .isInstanceOf(java.io.IOException.class);
    }
    assertThat(server.snapshot().get("activeHandlers")).isEqualTo(0);
  }
}
