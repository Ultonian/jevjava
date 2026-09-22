package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.HoldingDelivery;
import static net.codefinch.jev.HttpTestFixture.MODELS;
import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static net.codefinch.jev.HttpTestFixture.blockedCallerExecutor;
import static net.codefinch.jev.HttpTestFixture.callerHttp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpPublicationTest {
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

  @Test
  void rejectedExecutorFailsTheFutureCleanly() {
    ExecutorService dead = Executors.newSingleThreadExecutor();
    dead.shutdown();
    try (JevClient c = client().executor(dead).build()) {
      assertThatThrownBy(() -> c.modelsAsync().get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(JevException.class);
    }
  }

  /** Review P1: the deadline must cover executor queueing and expire independently of it. */
  @Test
  void queuedCallExpiresAtTheDeadlineWhileTheExecutorIsStillBlocked() throws Exception {
    server.enqueueJson(200, MODELS).enqueueJson(200, MODELS);
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    single.submit(() -> block.await(10, TimeUnit.SECONDS)); // occupies the only worker
    try (JevClient c = client().executor(single).deadline(Duration.ofMillis(150)).build()) {
      long start = System.nanoTime();
      CompletableFuture<ModelList> perClient = c.modelsAsync();
      CompletableFuture<ModelList> perCall =
          c.modelsAsync(
              RequestOptions.builder()
                  .deadline(Duration.ofMillis(200))
                  .retry(p -> RetryPolicy.NONE)
                  .build());
      assertThatThrownBy(() -> perClient.get(2, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(JevDeadlineExceededException.class)
          .cause()
          .hasMessageContaining("150 ms exceeded after 0 attempt(s)");
      assertThatThrownBy(() -> perCall.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      assertThat(block.getCount())
          .as("executor still blocked when the futures expired")
          .isEqualTo(1);
      block.countDown(); // release: the expired tasks must not send anything
      Thread.sleep(200);
      assertThat(server.requests()).isEmpty();
      assertThat(single.isShutdown()).isFalse();
    } finally {
      block.countDown();
      single.shutdownNow();
    }
  }

  /** Fix-review P1(1): a blocking application callback must not stall other calls' deadlines. */
  @Test
  void blockingCallbackDoesNotBlockOtherDeadlines() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    try (JevClient c = client().executor(single).deadline(Duration.ofMillis(150)).build()) {
      CompletableFuture<ModelList> first = c.modelsAsync();
      first.whenComplete(
          (r, t) -> {
            callbackStarted.countDown();
            try {
              callbackRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<ModelList> second = c.modelsAsync();
      assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
          .as("second deadline fires while the first callback is still blocked")
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      assertThat(callbackRelease.getCount()).isEqualTo(1);
    } finally {
      callbackRelease.countDown();
      occupied.countDown();
      single.shutdownNow();
    }
    assertThat(server.requests()).isEmpty();
  }

  /** Fix-review P1(2): close() stays bounded even when a completion callback blocks. */
  @Test
  void closeIsBoundedDespiteBlockingCallback() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    JevClient c =
        client().executor(single).noDeadline().closeGracePeriod(Duration.ofMillis(20)).build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    queued.whenComplete(
        (r, t) -> {
          callbackStarted.countDown();
          try {
            callbackRelease.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    Thread closer = new Thread(c::close);
    closer.start();
    try {
      assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
      closer.join(1000);
      assertThat(closer.isAlive())
          .as("close() returned while the callback is still blocked")
          .isFalse();
      assertThat(queued.isCancelled()).isTrue();
    } finally {
      callbackRelease.countDown();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  /**
   * Fix-review P1(3): cancellation kills the exchange before callbacks, so no retry can slip out.
   */
  @Test
  void cancelPreventsRetryWhileTheCancellationCallbackIsBlocked() throws Exception {
    server
        .enqueue(TestServer.Scripted.json(500, "{}").stallingHeaders(Duration.ofMillis(300)))
        .enqueueJson(200, OK);
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    try (JevClient c = client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      f.whenComplete(
          (r, t) -> {
            callbackStarted.countDown();
            try {
              callbackRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      awaitRequests(1);
      Thread canceller = new Thread(() -> f.cancel(true));
      canceller.start();
      assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(f.isCancelled()).isTrue();
      Thread.sleep(700); // the delayed 500 has arrived by now; a retry would have been sent
      assertThat(server.requests()).as("no retry while the callback is blocked").hasSize(1);
      callbackRelease.countDown();
      canceller.join(2000);
    } finally {
      callbackRelease.countDown();
    }
  }

  /** R3 P2(1), deterministic: close() must not return until queued results are published. */
  @ParameterizedTest(name = "grace={0}ms")
  @CsvSource({"0", "50"})
  void closeWaitsForPublicationButNotForCallbacks(long graceMillis) throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    JevClient c =
        client()
            .executor(single)
            .httpClient(callerHttp())
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(graceMillis))
            .delivery(delivery)
            .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    Thread closer = new Thread(c::close, "closer");
    closer.start();
    try {
      closer.join(300);
      assertThat(closer.isAlive()).as("close() waits while publication is held").isTrue();
      assertThat(queued.isDone()).isFalse();
      assertThat(delivery.held).hasSize(1);
      delivery.release();
      closer.join(2000);
      assertThat(closer.isAlive()).as("close() returns once the result is published").isFalse();
      assertThat(queued.isDone()).isTrue();
      assertThat(queued.isCancelled()).isTrue();
    } finally {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  /** R3 P2(1), the reviewer's probe with real delivery: never pending after close returns. */
  @Test
  void resultsAreAlwaysTerminalWhenCloseReturns() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpClient http = callerHttp();
    int pending = 0;
    try {
      for (int trial = 0; trial < 200; trial++) {
        JevClient c =
            client()
                .executor(single)
                .httpClient(http)
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
        CompletableFuture<ModelList> f = c.modelsAsync();
        c.close();
        if (!f.isDone()) {
          pending++;
        }
        assertThat(f.isCancelled()).isTrue();
      }
    } finally {
      occupied.countDown();
      single.shutdownNow();
    }
    assertThat(pending).as("pending after close, out of 200 trials").isZero();
    assertThat(server.requests()).isEmpty();
  }

  /**
   * R3 P2(2): every completion (deadline, failure, success) is published on an SDK virtual thread.
   */
  @Test
  void completionsArePublishedOnSdkVirtualThreadsForEveryOutcome() throws Exception {
    server.enqueueJson(500, "{}").enqueueJson(200, MODELS);
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService blocked = blockedCallerExecutor(occupied);
    ExecutorService caller = Executors.newFixedThreadPool(2, r -> new Thread(r, "caller-exec"));
    try {
      HoldingDelivery d1 = new HoldingDelivery();
      try (JevClient c =
          client().executor(blocked).deadline(Duration.ofMillis(100)).delivery(d1).build()) {
        CompletableFuture<ModelList> f = c.modelsAsync();
        assertSdkDeliveryThread(callbackThread(f, d1));
        assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
            .hasCauseInstanceOf(JevDeadlineExceededException.class);
      }
      HoldingDelivery d2 = new HoldingDelivery();
      try (JevClient c =
          client().executor(caller).retryPolicy(RetryPolicy.NONE).delivery(d2).build()) {
        CompletableFuture<ModelList> f1 = c.modelsAsync();
        assertSdkDeliveryThread(callbackThread(f1, d2));
        assertThatThrownBy(() -> f1.get(1, TimeUnit.SECONDS))
            .hasCauseInstanceOf(JevInternalServerException.class);
      }
      HoldingDelivery d3 = new HoldingDelivery();
      try (JevClient c =
          client().executor(caller).retryPolicy(RetryPolicy.NONE).delivery(d3).build()) {
        CompletableFuture<ModelList> f2 = c.modelsAsync();
        assertSdkDeliveryThread(callbackThread(f2, d3));
        assertThat(f2.get(1, TimeUnit.SECONDS).models()).hasSize(2);
      }
    } finally {
      occupied.countDown();
      blocked.shutdownNow();
      caller.shutdownNow();
    }
  }

  /**
   * R3 P2(2): a delivery that rejects, or a close() in the hand-off gap, never runs callbacks
   * inline.
   */
  @Test
  void rejectedDeliveryAndCloseInTheHandoffGapStillPublishOnVirtualThreads() throws Exception {
    // A delivery executor that rejects once released: the SDK must fall back to a fresh virtual
    // thread. The callback is registered before the rejection can happen.
    server.enqueueJson(200, MODELS);
    CountDownLatch rejectGate = new CountDownLatch(1);
    CountDownLatch reachedDelivery = new CountDownLatch(1);
    AtomicReference<Thread> rejectedPath = new AtomicReference<>();
    try (JevClient c =
        client()
            .delivery(
                r -> {
                  reachedDelivery.countDown();
                  try {
                    rejectGate.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  throw new java.util.concurrent.RejectedExecutionException("test");
                })
            .build()) {
      CompletableFuture<ModelList> f = c.modelsAsync();
      assertThat(reachedDelivery.await(3, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<?> stage =
          f.whenComplete((r, t) -> rejectedPath.set(Thread.currentThread()));
      rejectGate.countDown();
      stage.get(3, TimeUnit.SECONDS);
      assertThat(f.get(1, TimeUnit.SECONDS).models()).hasSize(2);
    }
    assertSdkDeliveryThread(rejectedPath.get());

    // close() while a deadline expiry sits in the gap between finish() and hand-off.
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService blocked = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    AtomicReference<Thread> gapPath = new AtomicReference<>();
    JevClient c =
        client()
            .executor(blocked)
            .httpClient(callerHttp())
            .deadline(Duration.ofMillis(50))
            .closeGracePeriod(Duration.ZERO)
            .delivery(delivery)
            .build();
    try {
      CompletableFuture<ModelList> f = c.modelsAsync();
      CompletableFuture<?> gapStage = f.whenComplete((r, t) -> gapPath.set(Thread.currentThread()));
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (delivery.held.isEmpty() && System.nanoTime() < end) {
        Thread.sleep(5); // the timer has won finish() and handed off; publication is held
      }
      assertThat(delivery.held).hasSize(1);
      Thread closer = new Thread(c::close, "closer");
      closer.start();
      closer.join(200);
      assertThat(closer.isAlive()).as("close() waits for the held publication").isTrue();
      delivery.release();
      closer.join(2000);
      assertThat(closer.isAlive()).isFalse();
      assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      gapStage.handle((r, t) -> null).get(3, TimeUnit.SECONDS);
      assertSdkDeliveryThread(gapPath.get());
    } finally {
      delivery.release();
      occupied.countDown();
      blocked.shutdownNow();
    }
  }

  /**
   * Registers a callback while publication is held, releases, and returns the callback's thread.
   */
  private static Thread callbackThread(CompletableFuture<?> f, HoldingDelivery delivery)
      throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (delivery.held.isEmpty() && System.nanoTime() < end) {
      Thread.sleep(2); // wait for the outcome to reach the (held) delivery seam
    }
    assertThat(delivery.held).as("publication is held").hasSize(1);
    AtomicReference<Thread> thread = new AtomicReference<>();
    CompletableFuture<?> stage = f.whenComplete((r, t) -> thread.set(Thread.currentThread()));
    delivery.release();
    stage.handle((r, t) -> null).get(3, TimeUnit.SECONDS); // the dependent stage: callback has run
    return thread.get();
  }

  private static void assertSdkDeliveryThread(Thread t) {
    assertThat(t).isNotNull();
    assertThat(t.isVirtual()).as("published on a virtual thread: " + t).isTrue();
    assertThat(t.getName()).doesNotContain("jev-deadline", "caller-exec", "closer", "main");
  }

  private void awaitRequests(int n) throws InterruptedException {
    fixture.awaitRequests(n);
  }
}
