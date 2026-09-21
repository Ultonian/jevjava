package net.codefinch.jev.micrometer;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Questions;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneResponse;
import org.junit.jupiter.api.Test;

/** Metrics through the real HTTP client: a 500 then a 200 on a local server. */
class JevMetricsIntegrationTest {
  private static final String OK =
      "{\"model\":\"jev-1.13.0\",\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":0.93}},"
          + "\"usage\":{\"input_tokens\":210,\"output_tokens\":31}}";

  @Test
  void realTransportRecordsAttemptsCallTokensAndAllowlistedConfidence() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          int status = hits.getAndIncrement() == 0 ? 500 : 200;
          byte[] body = (status == 500 ? "{}" : OK).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    JevMetrics metrics = JevMetrics.builder(registry).questionTags(Set.of("q")).build();
    try (JevClient client =
        JevClient.builder()
            .apiKey("k")
            .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .retryPolicy(
                net.codefinch.jev.RetryPolicy.DEFAULT.withBackoff(
                    java.time.Duration.ZERO, java.time.Duration.ZERO))
            .observer(metrics)
            .build()) {
      SystemOneResponse r =
          client.systemOne(State.of("s"), Questions.of("q", NoulQuestion.of("?")));
      assertThat(r.model()).isEqualTo("jev-1.13.0");
    } finally {
      server.stop(0);
    }
    // Events are delivered asynchronously; wait for the call timer to appear.
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (registry.find("jev.call").timer() == null && System.nanoTime() < end) {
      Thread.sleep(5);
    }
    assertThat(
            registry
                .get("jev.attempt")
                .tags("status", "500", "attempt", "1", "outcome", "error")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("jev.attempt")
                .tags("status", "200", "attempt", "2", "outcome", "success")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("jev.call")
                .tags(
                    "operation",
                    "systemone",
                    "outcome",
                    "success",
                    "status",
                    "200",
                    "model",
                    "jev-1.13.0")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("jev.tokens").tags("type", "input").counter().count()).isEqualTo(210);
    assertThat(registry.get("jev.tokens").tags("type", "output").counter().count()).isEqualTo(31);
    assertThat(registry.get("jev.noul").tags("question", "q").summary().mean()).isEqualTo(0.93);
  }
}
