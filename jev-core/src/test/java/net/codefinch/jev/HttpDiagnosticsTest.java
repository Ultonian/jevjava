package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.internal.HttpJevClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpDiagnosticsTest {
  private HttpTestFixture fixture;
  private TestServer server;

  @BeforeEach
  void start() throws IOException {
    fixture = new HttpTestFixture();
    server = fixture.server;
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  private JevClientBuilder client() {
    return fixture.client();
  }

  /** Review P2: every diagnostic honours the client's level; secrets never appear. */
  @Test
  void loggingHonoursTheClientLevelAndRedactsCredentials() {
    java.util.logging.Logger jul =
        java.util.logging.Logger.getLogger(HttpJevClient.class.getName());
    List<java.util.logging.LogRecord> records = new ArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Level previous = jul.getLevel();
    jul.setLevel(java.util.logging.Level.ALL);
    jul.addHandler(handler);
    try {
      for (System.Logger.Level level :
          List.of(
              System.Logger.Level.OFF,
              System.Logger.Level.WARNING,
              System.Logger.Level.INFO,
              System.Logger.Level.DEBUG)) {
        records.clear();
        server
            .enqueue(
                TestServer.Scripted.of(
                    500, Map.of("Set-Cookie", "session=abc", "X-Auth-Token", "tok"), "{}"))
            .enqueue(
                TestServer.Scripted.of(
                    200,
                    Map.of("Content-Type", "application/json", "x-typesafe-request-id", "req-log"),
                    OK));
        try (JevClient c =
            client()
                .logLevel(level)
                .defaultHeader("X-Api-Key", "default-secret")
                .defaultHeader("MY-SECRET-thing", "s3")
                .build()) {
          c.systemOne(REQUEST, RequestOptions.builder().header("x-refresh-TOKEN", "t0k").build());
        }
        String all = records.stream().map(r -> r.getMessage()).reduce("", (a, b) -> a + "\n" + b);
        switch (level) {
          case OFF -> assertThat(records).as("OFF logs nothing, not even the retry").isEmpty();
          case WARNING ->
              assertThat(records).as("WARNING (default): nothing for a healthy call").isEmpty();
          case INFO -> {
            assertThat(all).contains("retrying in", "-> 500 in", "-> 200 in", "request_id=req-log");
            assertThat(all).doesNotContain("headers ", "Bearer", "test-key");
          }
          case DEBUG -> {
            assertThat(all).contains("-> POST", "<- headers", "body {", "\"jev-1.13.0\"");
            assertThat(all)
                .contains(
                    "Authorization=[redacted]",
                    "X-Api-Key=[redacted]",
                    "MY-SECRET-thing=[redacted]",
                    "x-refresh-TOKEN=[redacted]")
                .contains("set-cookie=[redacted]", "x-auth-token=[redacted]"); // JDK lower-cases
            assertThat(all)
                .doesNotContain("test-key", "default-secret", "s3", "t0k", "session=abc", "tok\"");
          }
          default -> throw new AssertionError(level);
        }
      }
    } finally {
      jul.removeHandler(handler);
      jul.setLevel(previous);
    }
  }

  /** Review P3: diagnostics never render the key or credential-bearing default headers. */
  @Test
  void configToStringRedactsCredentials() {
    try (HttpJevClient c =
        (HttpJevClient)
            client()
                .defaultHeader("X-Api-Key", "hdr-secret")
                .defaultHeader("X-Trace", "visible")
                .defaultModel("jev-1.13.0")
                .build()) {
      String s = c.config().toString();
      assertThat(s).doesNotContain("test-key", "hdr-secret");
      assertThat(s)
          .contains(
              "apiKey=[redacted]",
              "X-Api-Key=[redacted]",
              "X-Trace=visible",
              "jev-1.13.0",
              "deadline=PT30S");
    }
    try (HttpJevClient c = (HttpJevClient) client().noDeadline().build()) {
      assertThat(c.config().toString()).contains("deadline=disabled");
    }
  }

  /** Fix-review P2: DEBUG retry lines never carry server body text; TRACE may. */
  @Test
  void debugRetryLogsCarryNoBodyText() {
    java.util.logging.Logger jul =
        java.util.logging.Logger.getLogger(HttpJevClient.class.getName());
    List<java.util.logging.LogRecord> records = new ArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Level previous = jul.getLevel();
    jul.setLevel(java.util.logging.Level.ALL);
    jul.addHandler(handler);
    try {
      for (System.Logger.Level level :
          List.of(
              System.Logger.Level.OFF,
              System.Logger.Level.WARNING,
              System.Logger.Level.INFO,
              System.Logger.Level.DEBUG)) {
        records.clear();
        server
            .enqueue(
                TestServer.Scripted.of(
                    500, Map.of("Content-Type", "text/plain"), "review-raw-body-marker"))
            .enqueue(
                TestServer.Scripted.of(
                    502,
                    Map.of("Content-Type", "application/json"),
                    "{\"code\":\"json-marker-7\"}"))
            .enqueue(
                TestServer.Scripted.of(
                    503,
                    Map.of("Content-Type", "application/json"),
                    "{\"message\":\"known-msg-marker\"}"))
            .enqueueJson(200, OK);
        try (JevClient c =
            client().logLevel(level).retryPolicy(RetryPolicy.DEFAULT.withMaxRetries(3)).build()) {
          c.systemOne(REQUEST);
        }
        String all = records.stream().map(r -> r.getMessage()).reduce("", (a, b) -> a + "\n" + b);
        switch (level) {
          case OFF, WARNING -> assertThat(records).isEmpty();
          case INFO -> {
            assertThat(all)
                .contains(
                    "retrying in 500ms (retry 1/3) after JevInternalServerException status=500",
                    "status=502",
                    "status=503");
            assertThat(all)
                .doesNotContain("review-raw-body-marker", "json-marker-7", "known-msg-marker");
          }
          case DEBUG ->
              assertThat(all)
                  .contains("review-raw-body-marker", "json-marker-7", "known-msg-marker");
          default -> throw new AssertionError(level);
        }
      }
    } finally {
      jul.removeHandler(handler);
      jul.setLevel(previous);
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"OFF, false", "ERROR, false", "WARNING, true"})
  void unknownAnswerAndObserverWarningsHonourEachClientsLevel(String level, boolean expected)
      throws Exception {
    CallObserver throwing =
        new CallObserver() {
          @Override
          public void onCall(Call call) {
            throw new IllegalStateException("observer boom");
          }
        };
    CountDownLatch observed = new CountDownLatch(1);
    CallObserver sentinel =
        new CallObserver() {
          @Override
          public void onCall(Call call) {
            observed.countDown();
          }
        };
    try (SdkLogCapture capture = new SdkLogCapture()) {
      server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
      try (JevClient c =
          client()
              .logLevel(System.Logger.Level.valueOf(level))
              .observer(throwing)
              .observer(sentinel)
              .build()) {
        c.systemOne(REQUEST);
      }
      assertThat(observed.await(3, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(50); // the throwing observer's warning is logged on the delivery thread
      String all = capture.messages();
      if (expected) {
        assertThat(all)
            .as("rendered text carries the real id and type, not {0}/{1}")
            .contains("Ignoring answer 'mystery' with unrecognized type 'aurora'")
            .contains("CallObserver.onCall threw; ignoring");
        assertThat(capture.records).anyMatch(r -> r.getThrown() instanceof IllegalStateException);
      } else {
        assertThat(capture.records)
            .as(level + " logs nothing, parser and observer paths included")
            .isEmpty();
      }
    }
  }

  @Test
  void twoClientsWithDifferentLevelsFilterIndependently() throws Exception {
    try (SdkLogCapture capture = new SdkLogCapture()) {
      server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
      server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
      try (JevClient quiet = client().logLevel(System.Logger.Level.OFF).build();
          JevClient loud = client().logLevel(System.Logger.Level.INFO).build()) {
        quiet.systemOne(REQUEST);
        assertThat(capture.records).isEmpty();
        loud.systemOne(REQUEST);
      }
      assertThat(capture.messages()).contains("unrecognized type 'aurora'", "-> 200 in");
      assertThat(capture.records).hasSizeGreaterThanOrEqualTo(2);
    }
  }

  /** Captures every record on the SDK's logger namespace, whichever class emitted it. */
  private static final class SdkLogCapture implements AutoCloseable {
    final List<java.util.logging.LogRecord> records =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.logging.Logger jul =
        java.util.logging.Logger.getLogger("net.codefinch.jev");
    private final java.util.logging.Level previous = jul.getLevel();
    private final java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    SdkLogCapture() {
      jul.setLevel(java.util.logging.Level.ALL);
      jul.addHandler(handler);
    }

    String messages() {
      return records.stream()
          .map(java.util.logging.LogRecord::getMessage)
          .reduce("", (a, b) -> a + "\n" + b);
    }

    @Override
    public void close() {
      jul.removeHandler(handler);
      jul.setLevel(previous);
    }
  }
}
