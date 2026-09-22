package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpDeadlineTest {
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

  @Test
  void stalledHeadersTimeOutPerAttemptAndAreRetried() {
    server
        .enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)))
        .enqueueJson(200, OK);
    try (JevClient c = client().timeout(Duration.ofMillis(200)).build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void stalledBodyTimesOutToo() {
    CountDownLatch release = new CountDownLatch(1);
    server.enqueue(TestServer.Scripted.json(200, OK).stallingBody(20, release));
    try (JevClient c =
        client().timeout(Duration.ofMillis(300)).retryPolicy(RetryPolicy.NONE).build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevTimeoutException.class)
          .isNotInstanceOf(JevDeadlineExceededException.class)
          .hasMessageContaining("timed out after 300 ms");
    } finally {
      release.countDown();
    }
  }

  @Test
  void deadlineRefusesRetryThatWouldBreachIt() {
    AtomicLong nanos = new AtomicLong();
    server.enqueueJson(500, "{\"message\":\"first\"}");
    try (JevClient c = client().nanoTime(nanos::get).deadline(Duration.ofMillis(400)).build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevDeadlineExceededException.class)
          .hasMessageContaining("400 ms exceeded after 1 attempt(s)")
          .hasCauseInstanceOf(JevInternalServerException.class);
    }
    assertThat(server.requests()).hasSize(1);
    assertThat(sleeps).isEmpty(); // 500 ms backoff >= 400 ms deadline: fail fast, no sleep
  }

  @Test
  void deadlineExpiringDuringAnActiveAttemptCancelsIt() {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (JevClient c =
        client().timeout(Duration.ofSeconds(10)).deadline(Duration.ofMillis(300)).build()) {
      long start = System.nanoTime();
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevDeadlineExceededException.class)
          .hasCauseInstanceOf(JevTimeoutException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void deadlineCanBeDisabledPerCallAndPerClient() {
    server.enqueueJson(500, "{}").enqueueJson(200, OK).enqueueJson(500, "{}").enqueueJson(200, OK);
    AtomicLong nanos = new AtomicLong();
    try (JevClient c = client().nanoTime(nanos::get).deadline(Duration.ofMillis(100)).build()) {
      assertThat(c.systemOne(REQUEST, RequestOptions.builder().noDeadline().build()).model())
          .isEqualTo("jev-1.13.0");
    }
    try (JevClient c = client().nanoTime(nanos::get).noDeadline().build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    assertThat(server.requests()).hasSize(4);
  }
}
