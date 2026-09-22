package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpRetryTest {
  private HttpTestFixture fixture;
  private TestServer server;
  private List<Duration> sleeps;

  @BeforeEach
  void start() throws IOException {
    fixture = new HttpTestFixture();
    server = fixture.server;
    sleeps = fixture.sleeps;
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  private JevClientBuilder client() {
    return fixture.client();
  }

  @ParameterizedTest
  @CsvSource({
    "400, JevBadRequestException",
    "401, JevAuthenticationException",
    "403, JevPermissionDeniedException",
    "404, JevNotFoundException",
    "422, JevUnprocessableEntityException",
    "418, JevApiException",
  })
  void nonRetriedStatusesMapWithoutRetrying(int status, String cls) {
    server.enqueue(
        TestServer.Scripted.of(
            status, Map.of("x-typesafe-request-id", "req-e"), "{\"message\":\"nope\"}"));
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevApiException.class)
          .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo(cls))
          .hasMessage(
              "POST " + server.baseUrl() + "/v1/systemone: " + status + " nope (request_id=req-e)");
    }
    assertThat(server.requests()).hasSize(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void retriesThenSucceedsWithRetryCountHeaderAndBackoff() {
    server.enqueueJson(429, "{}").enqueueJson(503, "{}").enqueueJson(200, OK);
    try (JevClient c = client().build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    List<TestServer.Recorded> reqs = server.requests();
    assertThat(reqs).hasSize(3);
    assertThat(reqs.get(0).header("X-TypeSafe-Retry-Count")).isNull();
    assertThat(reqs.get(1).header("X-TypeSafe-Retry-Count")).isEqualTo("1");
    assertThat(reqs.get(2).header("X-TypeSafe-Retry-Count")).isEqualTo("2");
    assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
  }

  @Test
  void retriesExhaustedThrowsTheLastFailure() {
    server
        .enqueueJson(500, "{\"message\":\"one\"}")
        .enqueueJson(502, "{\"message\":\"two\"}")
        .enqueueJson(529, "{\"message\":\"three\"}");
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevInternalServerException.class)
          .hasMessageContaining("529 three")
          .satisfies(e -> assertThat(((JevInternalServerException) e).isOverloaded()).isTrue());
    }
    assertThat(server.requests()).hasSize(3);
  }

  @Test
  void retryAfterIsHonouredPreferringMsAndIgnoredAboveTheCap() {
    server
        .enqueue(
            TestServer.Scripted.of(429, Map.of("retry-after-ms", "1234", "retry-after", "9"), ""))
        .enqueue(TestServer.Scripted.of(429, Map.of("retry-after", "2"), ""))
        .enqueue(TestServer.Scripted.of(503, Map.of("retry-after", "61"), ""))
        .enqueueJson(200, OK);
    try (JevClient c = client().retryPolicy(RetryPolicy.DEFAULT.withMaxRetries(3)).build()) {
      c.systemOne(REQUEST);
    }
    assertThat(sleeps)
        .containsExactly(Duration.ofMillis(1234), Duration.ofSeconds(2), Duration.ofMillis(2000));
  }

  @Test
  void perCallRetryOverrideIsPartial() {
    server.enqueueJson(500, "{}").enqueueJson(200, OK);
    try (JevClient c = client().build()) {
      assertThatThrownBy(
              () ->
                  c.systemOne(
                      REQUEST, RequestOptions.builder().retry(p -> p.withMaxRetries(0)).build()))
          .isInstanceOf(JevInternalServerException.class);
      assertThat(server.requests()).hasSize(1);
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
  }

  @Test
  void connectionFailureIsRetriedThenMapped() {
    server.close(); // nothing listening any more
    AtomicInteger attempts = new AtomicInteger();
    try (JevClient c =
        client()
            .retryPolicy(
                RetryPolicy.DEFAULT.withPredicate(
                    e -> {
                      attempts.incrementAndGet();
                      return false;
                    }))
            .build()) {
      assertThatThrownBy(() -> c.models())
          .isInstanceOf(JevConnectionException.class)
          .isNotInstanceOf(JevTimeoutException.class);
    }
    assertThat(sleeps).hasSize(2);
    assertThat(attempts.get())
        .isZero(); // built-in rule decided; predicate only consulted when it did not
  }

  @Test
  void predicateCanOptIntoRetryingValidationFailures() {
    server.enqueueJson(200, "not json").enqueueJson(200, OK);
    try (JevClient c =
        client()
            .retryPolicy(
                RetryPolicy.DEFAULT.withPredicate(e -> e instanceof JevResponseValidationException))
            .build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    assertThat(server.requests()).hasSize(2);
  }
}
