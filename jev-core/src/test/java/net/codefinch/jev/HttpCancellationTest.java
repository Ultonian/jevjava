package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static net.codefinch.jev.HttpTestFixture.blockedCallerExecutor;
import static net.codefinch.jev.HttpTestFixture.callerHttp;
import static net.codefinch.jev.HttpTestFixture.drain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.codefinch.jev.internal.HttpJevClient;
import net.codefinch.jev.internal.Sleeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpCancellationTest {
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
  void cancelBeforeStartNeverSendsRequest() throws Exception {
    ExecutorService gate = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    gate.submit(() -> block.await(10, TimeUnit.SECONDS)); // occupies the only thread
    try (JevClient c = client().executor(gate).build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      assertThat(f.cancel(true)).isTrue();
      block.countDown();
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
      Thread.sleep(100);
      assertThat(server.requests()).isEmpty();
    } finally {
      gate.shutdownNow();
    }
    assertThat(gate.isShutdown()).isTrue();
  }

  @Test
  void cancelDuringAnAttemptAbortsIt() throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (JevClient c = client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      awaitRequests(1);
      long start = System.nanoTime();
      f.cancel(true);
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void cancelDuringBackoffStartsNoFurtherAttemptEvenWithPermissivePredicate() throws Exception {
    server.enqueueJson(500, "{}").enqueueJson(200, OK);
    CountDownLatch sleeping = new CountDownLatch(1);
    Sleeper blocking =
        (delay, handle) -> {
          sleeping.countDown();
          return !handle.awaitCancel(Duration.ofSeconds(10));
        };
    try (JevClient c =
        client()
            .sleeper(blocking)
            .retryPolicy(RetryPolicy.DEFAULT.withPredicate(e -> true))
            .build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
      f.cancel(true);
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
    }
    Thread.sleep(100);
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void cancelRacingTheNextRetryIsRespectedOnBothExecutors() throws Exception {
    for (ExecutorService callerOwned :
        new ExecutorService[] {null, Executors.newFixedThreadPool(2)}) {
      server.enqueueJson(
          500, "{}"); // only one scripted response per iteration: a 2nd request must not happen
      AtomicReference<CompletableFuture<SystemOneResponse>> holder = new AtomicReference<>();
      // Cancel from inside the sleep: the worker returns "completed" but must still stop.
      Sleeper cancelFromInside =
          (delay, handle) -> {
            while (holder.get() == null) {
              Thread.onSpinWait(); // the caller may not have stored the future yet
            }
            holder.get().cancel(true);
            return true;
          };
      JevClientBuilder b = client().sleeper(cancelFromInside);
      if (callerOwned != null) {
        b.executor(callerOwned);
      }
      int before = server.requests().size();
      try (JevClient c = b.build()) {
        holder.set(c.systemOneAsync(REQUEST));
        assertThatThrownBy(() -> holder.get().get(2, TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);
      }
      Thread.sleep(100);
      assertThat(server.requests().size() - before).isEqualTo(1);
      if (callerOwned != null) {
        assertThat(callerOwned.isShutdown()).as("caller-owned executor untouched").isFalse();
        callerOwned.shutdownNow();
      }
    }
  }

  @Test
  void interruptingSyncCallThrowsAndReassertsTheFlag() throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (JevClient c = client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      Throwable[] thrown = new Throwable[1];
      boolean[] flag = new boolean[1];
      Thread t =
          new Thread(
              () -> {
                try {
                  c.systemOne(REQUEST);
                } catch (Throwable e) {
                  thrown[0] = e;
                  flag[0] = Thread.currentThread().isInterrupted();
                }
              });
      t.start();
      awaitRequests(1);
      t.interrupt();
      t.join(5000);
      assertThat(thrown[0]).isInstanceOf(JevInterruptedException.class);
      assertThat(flag[0]).as("interrupt flag re-asserted").isTrue();
    }
  }

  /** R4 P2(1): explicitly cancelled calls must not stay tracked, whatever state they were in. */
  @Test
  void explicitCancellationReleasesTrackingInEveryState() throws Exception {
    // (a) 100 cancellations before start on a blocked caller-owned executor (the reviewer's probe).
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
    List<CompletableFuture<ModelList>> futures = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      futures.add(c.modelsAsync());
    }
    futures.forEach(f -> f.cancel(true));
    futures.forEach(f -> assertThat(f.isCancelled()).isTrue());
    assertThat(c.trackedCalls()).as("after 100 cancellations").isZero();
    futures.get(0).cancel(true); // repeated cancellation is harmless
    occupied.countDown();
    drain(single);
    assertThat(c.trackedCalls()).as("after the worker drained").isZero();
    c.close();
    assertThat(c.trackedCalls()).as("after close").isZero();
    assertThat(server.requests()).isEmpty();
    single.shutdownNow();

    // (b) during an HTTP exchange.
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (HttpJevClient c2 =
        (HttpJevClient) client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c2.systemOneAsync(REQUEST);
      awaitRequests(1);
      f.cancel(true);
      assertThat(c2.trackedCalls()).as("during HTTP").isZero();
    }

    // (c) during backoff, with a blocking cancellation callback, cancelled from another thread.
    server.enqueueJson(500, "{}").enqueueJson(200, OK);
    CountDownLatch sleeping = new CountDownLatch(1);
    Sleeper blockingSleep =
        (delay, handle) -> {
          sleeping.countDown();
          return !handle.awaitCancel(Duration.ofSeconds(10));
        };
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    try (HttpJevClient c3 = (HttpJevClient) client().sleeper(blockingSleep).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c3.systemOneAsync(REQUEST);
      f.whenComplete(
          (r, t) -> {
            callbackStarted.countDown();
            try {
              callbackRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(sleeping.await(3, TimeUnit.SECONDS)).isTrue();
      Thread canceller = new Thread(() -> f.cancel(true), "canceller");
      canceller.start();
      assertThat(callbackStarted.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(f.isCancelled()).isTrue();
      callbackRelease.countDown();
      canceller.join(3000);
      assertThat(c3.trackedCalls())
          .as("during backoff, after the cancel callback returned")
          .isZero();
    } finally {
      callbackRelease.countDown();
    }

    // (d) cancellation racing close().
    CountDownLatch occupied2 = new CountDownLatch(1);
    ExecutorService single2 = blockedCallerExecutor(occupied2);
    HttpJevClient c4 =
        (HttpJevClient)
            client()
                .executor(single2)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
    List<CompletableFuture<ModelList>> racing = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      racing.add(c4.modelsAsync());
    }
    Thread closer = new Thread(c4::close, "closer");
    closer.start();
    racing.forEach(f -> f.cancel(true));
    closer.join(5000);
    assertThat(closer.isAlive()).isFalse();
    racing.forEach(f -> assertThat(f.isDone()).isTrue());
    // Entries that close() won are released by their delivery thread right after publication (and
    // after any callbacks), so the set drains a few microseconds after close() returns.
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (c4.trackedCalls() != 0 && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    assertThat(c4.trackedCalls()).as("after cancel/close race").isZero();
    occupied2.countDown();
    single2.shutdownNow();
  }

  private void awaitRequests(int n) throws InterruptedException {
    fixture.awaitRequests(n);
  }
}
