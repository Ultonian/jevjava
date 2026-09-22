package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CallObserverLifecycleTest {
  private static final String OK = Fixtures.read("responses/docs-all-three.json");
  private static final SystemOneRequest REQUEST =
      SystemOneRequest.of(State.of("s"), Questions.of("q", NoulQuestion.of("?")));

  private TestServer server;
  private final CallObserverTest.Recording recording = new CallObserverTest.Recording();

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

  /** An observer whose onCall blocks until released. */
  static final class Blocking implements CallObserver {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    final boolean blockAttempts;

    Blocking(boolean blockAttempts) {
      this.blockAttempts = blockAttempts;
    }

    private void block() {
      entered.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void onAttempt(Attempt attempt) {
      attempts.add(attempt);
      if (blockAttempts) {
        block();
      }
    }

    @Override
    public void onCall(Call call) {
      calls.add(call);
      block();
    }
  }

  @Test
  void blockedOnCallDoesNotStallOtherDeadlinesNorTheFailedResult() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    Blocking observer = new Blocking(false);
    try (JevClient c =
        JevClient.builder()
            .apiKey("k")
            .baseUrl(server.baseUrl())
            .observer(observer)
            .executor(single)
            .deadline(Duration.ofMillis(100))
            .build()) {
      CompletableFuture<ModelList> first = c.modelsAsync();
      assertThat(observer.entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> first.get(1, TimeUnit.SECONDS))
          .as("result published while observer is blocked")
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      CompletableFuture<ModelList> second = c.modelsAsync();
      assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS))
          .as("second deadline fires while observer is blocked")
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      assertThat(observer.release.getCount()).isEqualTo(1);
    } finally {
      observer.release.countDown();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  @Test
  void blockedOnCallDoesNotUnboundClose() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    Blocking observer = new Blocking(false);
    JevClient c =
        JevClient.builder()
            .apiKey("k")
            .baseUrl(server.baseUrl())
            .observer(observer)
            .executor(single)
            .httpClient(
                java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build())
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(20))
            .publicationTimeout(Duration.ofMillis(50))
            .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    Thread closer = new Thread(c::close, "closer");
    long start = System.nanoTime();
    closer.start();
    try {
      assertThat(observer.entered.await(2, TimeUnit.SECONDS)).isTrue();
      closer.join(1000);
      assertThat(closer.isAlive()).as("close() returned while the observer is blocked").isFalse();
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
      assertThat(queued.isCancelled()).isTrue();
    } finally {
      observer.release.countDown();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  @Test
  void blockedOnAttemptAndOnCallDoNotDelaySuccessOrCancellation() throws Exception {
    server.enqueueJson(200, OK).enqueueJson(200, OK);
    Blocking observer = new Blocking(true);
    try (JevClient c =
        JevClient.builder().apiKey("k").baseUrl(server.baseUrl()).observer(observer).build()) {
      long start = System.nanoTime();
      SystemOneResponse r = c.systemOne(REQUEST); // onAttempt blocks on a delivery thread, not here
      assertThat(r.model()).isEqualTo("jev-1.13.0");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      assertThat(observer.entered.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      assertThat(f.get(2, TimeUnit.SECONDS).model())
          .as("a second call completes while observers are blocked")
          .isEqualTo("jev-1.13.0");
    } finally {
      observer.release.countDown();
    }
    // Cancel-before-start on a separate client whose executor is held, so the models call provably
    // never starts (no scheduling race, no third attempt event).
    ExecutorService held = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    held.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    try (JevClient c2 =
        JevClient.builder()
            .apiKey("k")
            .baseUrl(server.baseUrl())
            .observer(observer)
            .executor(held)
            .noDeadline()
            .build()) {
      CompletableFuture<ModelList> cancelled = c2.modelsAsync();
      cancelled.cancel(true);
      assertThat(cancelled.isCancelled()).isTrue();
    } finally {
      occupied.countDown();
      held.shutdownNow();
    }
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while ((observer.attempts.size() < 2 || observer.calls.size() < 3) && System.nanoTime() < end) {
      Thread.sleep(5);
    }
    assertThat(observer.attempts).hasSize(2);
    // Ordering is per call: the cancelled call's own queue was never blocked.
    assertThat(observer.calls)
        .extracting(CallObserver.Call::outcome)
        .containsExactlyInAnyOrder(
            CallObserver.Outcome.SUCCESS,
            CallObserver.Outcome.SUCCESS,
            CallObserver.Outcome.CANCELLED);
  }

  @Test
  void eventsOfOneCallArriveInOrderOnVirtualThreads() throws Exception {
    server.enqueueJson(500, "{}").enqueueJson(429, "{}").enqueueJson(200, OK);
    List<String> order = new CopyOnWriteArrayList<>();
    List<Thread> threads = new CopyOnWriteArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    CallObserver ordering =
        new CallObserver() {
          @Override
          public void onAttempt(Attempt attempt) {
            threads.add(Thread.currentThread());
            order.add("attempt" + attempt.attempt());
          }

          @Override
          public void onCall(Call call) {
            threads.add(Thread.currentThread());
            order.add("call:" + call.outcome());
            done.countDown();
          }
        };
    try (JevClient c = client().observer(ordering).build()) {
      c.systemOne(REQUEST);
    }
    assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
    assertThat(order).containsExactly("attempt1", "attempt2", "attempt3", "call:SUCCESS");
    assertThat(threads).allMatch(Thread::isVirtual);
    assertThat(threads).noneMatch(t -> t.getName().contains("jev-deadline"));
  }

  /** R2 finding 3: a terminal event never overtakes an attempt that had already started. */
  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"cancel", "deadline", "close"})
  void attemptStartedBeforeTerminationIsDeliveredBeforeTheTerminalEvent(String path)
      throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    List<String> order = new CopyOnWriteArrayList<>();
    CountDownLatch terminal = new CountDownLatch(1);
    CallObserver ordering =
        new CallObserver() {
          @Override
          public void onAttempt(Attempt attempt) {
            order.add("attempt" + attempt.attempt());
          }

          @Override
          public void onCall(Call call) {
            order.add("call:" + call.outcome());
            terminal.countDown();
          }
        };
    JevClientBuilder b =
        JevClient.builder()
            .apiKey("k")
            .baseUrl(server.baseUrl())
            .observer(ordering)
            .timeout(Duration.ofSeconds(10))
            .closeGracePeriod(Duration.ZERO);
    JevClient c =
        (path.equals("deadline") ? b.deadline(Duration.ofMillis(300)) : b.noDeadline()).build();
    CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (server.requests().isEmpty() && System.nanoTime() < end) {
      Thread.sleep(5); // the worker is now inside the attempt, waiting on the stalled exchange
    }
    assertThat(server.requests()).hasSize(1);
    switch (path) {
      case "cancel" -> f.cancel(true);
      case "close" -> c.close();
      default -> {
        // deadline: nothing to do, it expires on its own
      }
    }
    assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
    String expected = path.equals("deadline") ? "call:DEADLINE" : "call:CANCELLED";
    assertThat(order).as(path).containsExactly("attempt1", expected);
    c.close();
  }

  /**
   * R4 finding: a failure while building the request (before any exchange) must still settle the
   * attempt's reserved slot, or the terminal ERROR event is stuck behind it forever.
   */
  @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
  @org.junit.jupiter.params.provider.ValueSource(strings = {"sync", "async"})
  void requestConstructionFailureIsObservedAsAnErrorCall(String mode) throws Exception {
    RequestOptions invalid = RequestOptions.builder().header("bad header", "value").build();
    try (JevClient c = client().build()) {
      if (mode.equals("sync")) {
        assertThatThrownBy(() -> c.models(invalid))
            .isInstanceOf(JevException.class)
            .hasMessageContaining("invalid request header");
      } else {
        CompletableFuture<ModelList> f = c.modelsAsync(invalid);
        assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(JevException.class)
            .cause()
            .hasMessageContaining("invalid request header");
      }
      awaitEvents(1);
      assertThat(server.requests()).isEmpty();
      assertThat(recording.calls).hasSize(1);
      CallObserver.Call call = recording.calls.get(0);
      assertThat(call.outcome()).isEqualTo(CallObserver.Outcome.ERROR);
      assertThat(call.attempts()).isEqualTo(1);
      assertThat(call.status()).isEmpty();
      assertThat(call.failure()).get().isInstanceOf(JevException.class);
      assertThat(recording.attempts).hasSize(1);
      CallObserver.Attempt attempt = recording.attempts.get(0);
      assertThat(attempt.attempt()).isEqualTo(1);
      assertThat(attempt.status()).isEmpty();
      assertThat(attempt.failure()).get().isInstanceOf(JevException.class);

      // Positive control: the client is not wedged, a valid call still observes normally.
      server.enqueueJson(200, Fixtures.read("responses/models.json"));
      c.models();
      awaitEvents(2);
      assertThat(recording.calls).hasSize(2);
      assertThat(recording.calls.get(1).outcome()).isEqualTo(CallObserver.Outcome.SUCCESS);
    }
  }

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
}
