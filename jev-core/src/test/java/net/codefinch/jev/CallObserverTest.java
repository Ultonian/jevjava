package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.internal.Sleeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallObserverTest {
  private static final String OK = Fixtures.read("responses/docs-all-three.json");
  private static final SystemOneRequest REQUEST =
      SystemOneRequest.of(State.of("s"), Questions.of("q", NoulQuestion.of("?")));

  /** Records every event. */
  static final class Recording implements CallObserver {
    final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public void onAttempt(Attempt attempt) {
      attempts.add(attempt);
    }

    @Override
    public void onCall(Call call) {
      calls.add(call);
    }
  }

  private TestServer server;
  private final Recording recording = new Recording();

  @BeforeEach
  void start() throws IOException {
    server = new TestServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private JevClientBuilder client() {
    return JevClient.builder()
        .apiKey("k")
        .baseUrl(server.baseUrl())
        .observer(recording)
        .sleeper((delay, handle) -> !handle.isCancelled());
  }

  @Test
  void successEmitsOneAttemptAndOneCallWithModelAndResponse() {
    server.enqueueJson(200, OK);
    try (JevClient c = client().build()) {
      c.systemOne(REQUEST);
    }
    awaitEvents(1);
    assertThat(recording.attempts).hasSize(1);
    CallObserver.Attempt a = recording.attempts.get(0);
    assertThat(a.operation()).isEqualTo("systemone");
    assertThat(a.attempt()).isEqualTo(1);
    assertThat(a.status()).hasValue(200);
    assertThat(a.failure()).isEmpty();
    assertThat(a.elapsed()).isPositive();
    assertThat(recording.calls).hasSize(1);
    CallObserver.Call call = recording.calls.get(0);
    assertThat(call.outcome()).isEqualTo(CallObserver.Outcome.SUCCESS);
    assertThat(call.attempts()).isEqualTo(1);
    assertThat(call.status()).hasValue(200);
    assertThat(call.model()).contains("jev-1.13.0");
    assertThat(call.response()).isPresent();
    assertThat(call.failure()).isEmpty();
  }

  @Test
  void retriesEmitOneAttemptEachAndOneCall() {
    server.enqueueJson(500, "{}").enqueueJson(429, "{}").enqueueJson(200, OK);
    try (JevClient c = client().build()) {
      c.systemOne(REQUEST);
    }
    awaitEvents(1);
    assertThat(recording.attempts)
        .extracting(a -> a.status().getAsInt())
        .containsExactly(500, 429, 200);
    assertThat(recording.attempts)
        .extracting(CallObserver.Attempt::attempt)
        .containsExactly(1, 2, 3);
    assertThat(recording.attempts.get(0).failure())
        .containsInstanceOf(JevInternalServerException.class);
    assertThat(recording.calls).hasSize(1);
    assertThat(recording.calls.get(0).attempts()).isEqualTo(3);
    assertThat(recording.calls.get(0).outcome()).isEqualTo(CallObserver.Outcome.SUCCESS);
  }

  @Test
  void errorOutcomeCarriesTheFailureAndLastStatus() {
    server.enqueueJson(400, "{\"message\":\"bad\"}");
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.models()).isInstanceOf(JevBadRequestException.class);
    }
    awaitEvents(1);
    CallObserver.Call call = recording.calls.get(0);
    assertThat(call.operation()).isEqualTo("models");
    assertThat(call.outcome()).isEqualTo(CallObserver.Outcome.ERROR);
    assertThat(call.status()).hasValue(400);
    assertThat(call.failure()).containsInstanceOf(JevBadRequestException.class);
    assertThat(call.model()).isEmpty();
    assertThat(call.response()).isEmpty();
  }

  @Test
  void transportFailureHasNoStatus() {
    server.close();
    try (JevClient c = client().retryPolicy(RetryPolicy.NONE).build()) {
      assertThatThrownBy(() -> c.models()).isInstanceOf(JevConnectionException.class);
    }
    awaitEvents(1);
    assertThat(recording.attempts).hasSize(1);
    assertThat(recording.attempts.get(0).status()).isEmpty();
    assertThat(recording.attempts.get(0).failure())
        .containsInstanceOf(JevConnectionException.class);
    assertThat(recording.calls.get(0).status()).isEmpty();
  }

  @Test
  void validationFailureIsAnErrorWithStatus200() {
    server.enqueueJson(200, "not json");
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.models()).isInstanceOf(JevResponseValidationException.class);
    }
    awaitEvents(1);
    assertThat(recording.attempts.get(0).status()).hasValue(200);
    assertThat(recording.attempts.get(0).failure())
        .containsInstanceOf(JevResponseValidationException.class);
    assertThat(recording.calls.get(0).outcome()).isEqualTo(CallObserver.Outcome.ERROR);
  }

  @Test
  void deadlineAndCancellationOutcomesForQueuedAsyncCalls() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    single.submit(() -> block.await(10, TimeUnit.SECONDS));
    try (JevClient c = client().executor(single).deadline(Duration.ofMillis(100)).build()) {
      CompletableFuture<ModelList> expired = c.modelsAsync();
      assertThatThrownBy(() -> expired.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      awaitEvents(1); // events of different calls are unordered relative to each other
      CompletableFuture<ModelList> cancelled =
          c.modelsAsync(RequestOptions.builder().noDeadline().build());
      cancelled.cancel(true);
      assertThatThrownBy(() -> cancelled.get(1, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
    } finally {
      block.countDown();
      single.shutdownNow();
    }
    awaitEvents(2);
    assertThat(recording.calls)
        .extracting(CallObserver.Call::outcome)
        .containsExactly(CallObserver.Outcome.DEADLINE, CallObserver.Outcome.CANCELLED);
    assertThat(recording.calls.get(0).attempts()).isZero();
    assertThat(recording.calls.get(0).failure())
        .containsInstanceOf(JevDeadlineExceededException.class);
    assertThat(recording.calls.get(0).elapsed()).isGreaterThanOrEqualTo(Duration.ofMillis(100));
    assertThat(recording.attempts).isEmpty();
  }

  @Test
  void syncDeadlineIsObservedOnce() {
    server.enqueueJson(500, "{}");
    try (JevClient c =
        client()
            .deadline(Duration.ofMillis(200))
            .retryPolicy(
                RetryPolicy.DEFAULT.withBackoff(Duration.ofSeconds(5), Duration.ofSeconds(5)))
            .build()) {
      assertThatThrownBy(() -> c.models()).isInstanceOf(JevDeadlineExceededException.class);
    }
    awaitEvents(1);
    assertThat(recording.calls).hasSize(1);
    assertThat(recording.calls.get(0).outcome()).isEqualTo(CallObserver.Outcome.DEADLINE);
    assertThat(recording.calls.get(0).attempts()).isEqualTo(1);
  }

  @Test
  void throwingObserversAreIgnoredAndOthersStillRun() {
    server.enqueueJson(200, OK);
    CallObserver bad =
        new CallObserver() {
          @Override
          public void onAttempt(Attempt attempt) {
            throw new IllegalStateException("boom");
          }

          @Override
          public void onCall(Call call) {
            throw new IllegalStateException("boom");
          }
        };
    try (JevClient c =
        JevClient.builder()
            .apiKey("k")
            .baseUrl(server.baseUrl())
            .observer(bad)
            .observer(recording)
            .build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    awaitEvents(1);
    assertThat(recording.attempts).hasSize(1);
    assertThat(recording.calls).hasSize(1);
  }

  // ---- Phase 3 review P1: observers are isolated from lifecycle threads ---------------------

  private void awaitEvents(int calls) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (recording.calls.size() < calls && System.nanoTime() < end) {
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Test
  void noObserversMeansNoWork() {
    server.enqueueJson(200, OK);
    try (JevClient c =
        JevClient.builder().apiKey("k").baseUrl(server.baseUrl()).sleeper(Sleeper.REAL).build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
  }
}
