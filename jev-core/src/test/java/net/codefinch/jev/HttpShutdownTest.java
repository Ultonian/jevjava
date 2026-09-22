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
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.codefinch.jev.internal.HttpJevClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpShutdownTest {
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
  void closeWaitsThenCancelsAndRejectsNewCalls() throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    ExecutorService callerOwned = Executors.newCachedThreadPool();
    HttpClient callerHttp = HttpClient.newHttpClient();
    JevClient c =
        client()
            .executor(callerOwned)
            .httpClient(callerHttp)
            .timeout(Duration.ofSeconds(10))
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(300))
            .build();
    CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
    awaitRequests(1);
    long start = System.nanoTime();
    c.close();
    c.close(); // idempotent
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
    assertThatThrownBy(() -> c.systemOne(REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> c.modelsAsync()).isInstanceOf(IllegalStateException.class);
    assertThat(callerOwned.isShutdown()).as("caller-owned executor never shut down").isFalse();
    assertThat(callerHttp.isTerminated()).as("caller-owned HttpClient never shut down").isFalse();
    callerOwned.shutdownNow();
  }

  @Test
  void closeWithSdkOwnedResourcesTerminatesThem() {
    server.enqueueJson(200, OK);
    HttpJevClient c = (HttpJevClient) client().build();
    c.systemOne(REQUEST);
    c.close();
    assertThat(c.config().executor().isShutdown()).isTrue();
    assertThat(c.config().httpClient().isTerminated()).isTrue();
  }

  /** Review P1: close() must complete queued futures even if their worker never runs. */
  @Test
  void closeCompletesQueuedFuturesBeforeTheirWorkerEverRuns() throws Exception {
    server.enqueueJson(200, MODELS);
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    single.submit(() -> block.await(10, TimeUnit.SECONDS));
    HttpClient callerHttp =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    JevClient c =
        client()
            .executor(single)
            .httpClient(callerHttp) // caller-owned: no SDK shutdown work can mask ordering gaps
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(30))
            .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    c.close();
    assertThat(queued.isDone()).as("completed by close(), worker still blocked").isTrue();
    assertThat(queued.isCancelled()).isTrue();
    assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
    assertThat(queued.handle((r, t) -> t).join())
        .isInstanceOf(CancellationException.class)
        .hasMessageContaining("client closed");
    assertThat(single.isShutdown()).as("caller-owned executor untouched").isFalse();
    List<Runnable> discarded = single.shutdownNow(); // caller discards the queued task
    assertThat(discarded).hasSize(1);
    block.countDown();
    Thread.sleep(100);
    assertThat(server.requests()).isEmpty();
  }

  /**
   * Fix-review P2: a call racing close() is either admitted (and cancelled) or rejected — never
   * lost.
   */
  @ParameterizedTest(name = "async={0} deadline={1}")
  @CsvSource({"true, true", "true, false", "false, true", "false, false"})
  void callRacingCloseNeverEscapesShutdown(boolean async, boolean withDeadline) throws Exception {
    ExecutorService callerExecutor = Executors.newCachedThreadPool();
    HttpClient callerHttp =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    server.enqueueJson(200, MODELS);
    CountDownLatch inOverride = new CountDownLatch(1);
    CountDownLatch releaseOverride = new CountDownLatch(1);
    RequestOptions pausing =
        RequestOptions.builder()
            .retry(
                p -> {
                  inOverride.countDown(); // requireOpen would have passed here in the old code
                  try {
                    releaseOverride.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return p;
                })
            .build();
    JevClientBuilder b = client().executor(callerExecutor).httpClient(callerHttp);
    JevClient c = (withDeadline ? b : b.noDeadline()).build();
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                if (async) {
                  c.modelsAsync(pausing).get(5, TimeUnit.SECONDS);
                } else {
                  c.models(pausing);
                }
              } catch (Throwable t) {
                outcome.set(t);
              }
            });
    caller.start();
    assertThat(inOverride.await(2, TimeUnit.SECONDS)).isTrue();
    c.close();
    releaseOverride.countDown();
    caller.join(5000);
    assertThat(outcome.get()).as("the racing call must fail, not succeed").isNotNull();
    Throwable t = outcome.get();
    assertThat(
            t instanceof IllegalStateException
                || t instanceof CancellationException
                || (t instanceof ExecutionException
                    && t.getCause() instanceof CancellationException))
        .as("rejected at admission or cancelled by close, got " + t)
        .isTrue();
    Thread.sleep(200);
    assertThat(server.requests()).as("no request after close() returned").isEmpty();
    assertThat(callerExecutor.isShutdown()).isFalse();
    callerExecutor.shutdownNow();
  }

  /** R4 P2(2): if publication cannot be established within its timeout, close() throws. */
  @Test
  void publicationTimeoutMakesCloseThrowAfterShuttingDownOwnedResources() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery(); // never released until close has returned
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single) // caller-owned: must not be shut down
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .publicationTimeout(Duration.ofMillis(150))
                .delivery(delivery)
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    long start = System.nanoTime();
    try {
      assertThatThrownBy(c::close)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("1 result(s) still unpublished (timed_out")
          .hasMessageContaining("publication timeout 150 ms");
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      assertThat(elapsed).isBetween(Duration.ofMillis(150), Duration.ofSeconds(2));
      assertThat(queued.isDone()).as("the guarantee was not met, and close said so").isFalse();
      // shutdownNow() was issued with a zero grace budget; termination itself is asynchronous.
      assertThat(c.config().httpClient().awaitTermination(Duration.ofSeconds(2)))
          .as("owned HttpClient shut down anyway")
          .isTrue();
      assertThat(single.isShutdown()).as("caller-owned executor untouched").isFalse();
      // A second close while publication is still held must not report success either.
      assertThatThrownBy(c::close)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("1 result(s) still unpublished (timed_out");
    } finally {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
    assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
    c.close(); // once the result has been published, a later close returns normally and truthfully
    assertThat(queued.isDone()).isTrue();
  }

  /**
   * R4 P2(2): interrupting the closing thread during the publication wait throws and re-asserts.
   */
  @Test
  void interruptedPublicationWaitMakesCloseThrowAndReassertTheFlag() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .publicationTimeout(Duration.ofSeconds(10))
                .delivery(delivery)
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>(false);
    Thread closer =
        new Thread(
            () -> {
              try {
                c.close();
              } catch (Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
              }
            },
            "closer");
    closer.start();
    try {
      Thread.sleep(100); // inside the publication wait
      closer.interrupt();
      closer.join(3000);
      assertThat(closer.isAlive()).isFalse();
      assertThat(thrown.get()).isInstanceOf(JevException.class).hasMessageContaining("interrupted");
      assertThat(flag.get()).as("interrupt flag re-asserted").isTrue();
      assertThat(queued.isDone()).isFalse();
    } finally {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  /** R5: a concurrent close() returns only after the first closer's shutdown, with results done. */
  @Test
  void concurrentCloseObservesTheFirstClosersOutcome() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ofMillis(600))
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    // The guarantee is that the second close returns only after the first closer's shutdown has
    // completed; the first thread itself may still be unwinding, so its liveness is not the check.
    AtomicReference<Boolean> shutdownDoneWhenSecondReturned = new AtomicReference<>();
    AtomicReference<Boolean> resultDoneWhenSecondReturned = new AtomicReference<>();
    Thread first = new Thread(c::close, "first-closer");
    first.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!c.isClosed() && System.nanoTime() < end) {
      Thread.sleep(1); // admission closed: the first closer is now inside its grace wait
    }
    assertThat(c.isClosed()).isTrue();
    assertThat(first.isAlive()).isTrue();
    Thread second =
        new Thread(
            () -> {
              c.close();
              shutdownDoneWhenSecondReturned.set(c.isShutdownComplete());
              resultDoneWhenSecondReturned.set(queued.isDone());
            },
            "second-closer");
    second.start();
    second.join(100);
    assertThat(second.isAlive()).as("second close waits for the first").isTrue();
    second.join(5000);
    first.join(5000);
    assertThat(second.isAlive()).isFalse();
    assertThat(resultDoneWhenSecondReturned.get())
        .as("result done when the second close returned")
        .isTrue();
    assertThat(shutdownDoneWhenSecondReturned.get())
        .as("first closer's shutdown complete when the second close returned")
        .isTrue();
    assertThat(queued.isCancelled()).isTrue();
    occupied.countDown();
    single.shutdownNow();
  }

  /** R5: repeated close after a successful shutdown is a truthful no-op. */
  @Test
  void repeatedCloseAfterSuccessfulShutdownReturnsNormally() throws Exception {
    server.enqueueJson(200, MODELS);
    HttpJevClient c = (HttpJevClient) client().build();
    CompletableFuture<ModelList> f = c.modelsAsync();
    f.get(5, TimeUnit.SECONDS);
    c.close();
    c.close();
    c.close();
    assertThat(f.isDone()).isTrue();
    assertThat(c.trackedCalls()).isZero();
    assertThatThrownBy(c::models).isInstanceOf(IllegalStateException.class);
  }

  /**
   * R5: a concurrent closer that is interrupted throws and re-asserts, without touching shutdown.
   */
  @Test
  void interruptedConcurrentCloserThrowsAndReassertsTheFlag() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ofMillis(600))
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    Thread first = new Thread(c::close, "first-closer");
    first.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!c.isClosed() && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>(false);
    Thread second =
        new Thread(
            () -> {
              try {
                c.close();
              } catch (Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
              }
            },
            "second-closer");
    second.start();
    Thread.sleep(50);
    second.interrupt();
    second.join(2000);
    assertThat(second.isAlive()).isFalse();
    assertThat(thrown.get())
        .isInstanceOf(JevException.class)
        .hasMessageContaining("interrupted while waiting for shutdown");
    assertThat(flag.get()).as("interrupt flag re-asserted").isTrue();
    first.join(5000);
    assertThat(first.isAlive()).as("the first close was unaffected").isFalse();
    assertThat(queued.isCancelled()).isTrue();
    c.close(); // now everything is done: a truthful normal return
    occupied.countDown();
    single.shutdownNow();
  }

  /** R6: overlapping closers, publication held past both — the second fails within ONE budget. */
  @ParameterizedTest(name = "grace={0}ms")
  @CsvSource({"0", "50"})
  void concurrentCloserSpendsAtMostOneGracePlusPublicationBudget(long graceMillis)
      throws Exception {
    Duration grace = Duration.ofMillis(graceMillis);
    Duration publication = Duration.ofMillis(400);
    HeldClose h = heldClose(grace, publication);
    try {
      Thread first = startFirstCloser(h.client());
      Thread.sleep(graceMillis + 50); // the first closer is ~50 ms into its publication wait
      long start = System.nanoTime();
      assertThatThrownBy(h.client()::close)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("still unpublished (timed_out");
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      Duration budget = grace.plus(publication);
      assertThat(elapsed)
          .as("second close bounded by one grace + publication budget (plus scheduling tolerance)")
          .isLessThan(budget.plus(Duration.ofMillis(150)))
          .isGreaterThan(
              Duration.ofMillis(300)); // it did wait for the first closer, then for publication
      first.join(2000);
      assertThat(first.isAlive()).isFalse();
      assertThat(h.queued().isDone()).isFalse();
    } finally {
      h.cleanup();
    }
  }

  /** R6: publication arriving just before the second closer's deadline yields a normal return. */
  @Test
  void concurrentCloserReturnsNormallyWhenPublicationArrivesBeforeItsDeadline() throws Exception {
    HeldClose h = heldClose(Duration.ZERO, Duration.ofMillis(400));
    try {
      Thread first = startFirstCloser(h.client());
      Thread.sleep(50);
      AtomicReference<Throwable> secondOutcome = new AtomicReference<>();
      Thread second =
          new Thread(
              () -> {
                try {
                  h.client().close();
                } catch (Throwable t) {
                  secondOutcome.set(t);
                }
              },
              "second-closer");
      second.start();
      first.join(
          2000); // first closer times out at ~400 ms; second is now in its own remaining wait
      assertThat(second.isAlive()).isTrue();
      h.delivery().release(); // publish ~50 ms before the second closer's deadline
      second.join(2000);
      assertThat(second.isAlive()).isFalse();
      assertThat(secondOutcome.get()).as("second close returned normally").isNull();
      assertThat(h.queued().isDone()).isTrue();
    } finally {
      h.cleanup();
    }
  }

  /** R6: interrupting the second closer during its publication remainder throws and re-asserts. */
  @Test
  void concurrentCloserInterruptedDuringPublicationRemainderThrows() throws Exception {
    HeldClose h = heldClose(Duration.ZERO, Duration.ofSeconds(5));
    try {
      Thread first = startFirstCloser(h.client());
      Thread.sleep(50);
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      AtomicReference<Boolean> flag = new AtomicReference<>(false);
      Thread second =
          new Thread(
              () -> {
                try {
                  h.client().close();
                } catch (Throwable t) {
                  thrown.set(t);
                  flag.set(Thread.currentThread().isInterrupted());
                }
              },
              "second-closer");
      second.start();
      Thread.sleep(100);
      second.interrupt();
      second.join(2000);
      assertThat(second.isAlive()).isFalse();
      assertThat(thrown.get()).isInstanceOf(JevException.class).hasMessageContaining("interrupted");
      assertThat(flag.get()).as("interrupt flag re-asserted").isTrue();
      h.delivery().release();
      first.join(6000);
      assertThat(first.isAlive()).isFalse();
    } finally {
      h.cleanup();
    }
  }

  /** Client with a queued call on a blocked caller executor and held publication. */
  private record HeldClose(
      HttpJevClient client,
      CompletableFuture<ModelList> queued,
      HoldingDelivery delivery,
      CountDownLatch occupied,
      ExecutorService single) {
    void cleanup() {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  private HeldClose heldClose(Duration grace, Duration publicationTimeout) {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(grace)
                .publicationTimeout(publicationTimeout)
                .delivery(delivery)
                .build();
    return new HeldClose(c, c.modelsAsync(), delivery, occupied, single);
  }

  /** Starts the first closer and returns once it has closed admission (inside its waits). */
  private static Thread startFirstCloser(HttpJevClient c) throws Exception {
    Thread first =
        new Thread(
            () -> {
              try {
                c.close();
              } catch (JevException expected) {
                // publication held: the first closer reports the timeout
              }
            },
            "first-closer");
    first.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!c.isClosed() && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    assertThat(c.isClosed()).isTrue();
    return first;
  }

  private void awaitRequests(int n) throws InterruptedException {
    fixture.awaitRequests(n);
  }
}
