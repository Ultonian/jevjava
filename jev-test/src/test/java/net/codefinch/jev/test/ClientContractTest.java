package net.codefinch.jev.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.NoulQuestion;
import net.codefinch.jev.Questions;
import net.codefinch.jev.State;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The same public lifecycle guarantees, exercised through both client implementations. */
class ClientContractTest {
  private static final SystemOneRequest REQUEST =
      SystemOneRequest.of(State.of("s"), Questions.of("q", NoulQuestion.of("?")));
  private static final SystemOneResponse RESPONSE =
      ScriptedAnswers.neutralResponse(REQUEST.questions());

  enum Backend {
    HTTP,
    RECORDING
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void closedClientsRejectSyncAndAsyncCalls(Backend backend) throws Exception {
    try (Fixture f = new Fixture(backend, false)) {
      f.client.close();
      assertThatThrownBy(() -> f.client.systemOne(REQUEST))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> f.client.systemOneAsync(REQUEST))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(f.client::models).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(f.client::modelsAsync).isInstanceOf(IllegalStateException.class);
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void asyncReturnsWhileWorkIsQueuedAndPublishesOnVirtualThread(Backend backend) throws Exception {
    try (Fixture f = new Fixture(backend, false)) {
      CompletableFuture<SystemOneResponse> result = f.client.systemOneAsync(REQUEST);
      assertThat(result).isNotDone();
      CompletableFuture<Thread> callback = result.thenApply(r -> Thread.currentThread());
      f.startWork();
      assertThat(result.get(5, TimeUnit.SECONDS).model()).isEqualTo(RESPONSE.model());
      assertThat(callback.get(5, TimeUnit.SECONDS).isVirtual()).isTrue();
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void cancelBeforeWorkPreventsSuccessfulPublication(Backend backend) throws Exception {
    try (Fixture f = new Fixture(backend, false)) {
      CompletableFuture<SystemOneResponse> result = f.client.systemOneAsync(REQUEST);
      CompletableFuture<Thread> callback = result.handle((r, t) -> Thread.currentThread());
      assertThat(result.cancel(true)).isTrue();
      assertThat(callback.get(5, TimeUnit.SECONDS)).isSameAs(Thread.currentThread());
      f.startWork();
      f.drainWorker();
      assertThat(result).isCancelled();
      assertThat(f.responseEntered.getCount()).as("no HTTP request or fake responder ran").isOne();
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void closePublishesAllAdmittedResultsAndPreservesCallerExecutor(Backend backend)
      throws Exception {
    try (Fixture f = new Fixture(backend, false)) {
      CompletableFuture<SystemOneResponse> first = f.client.systemOneAsync(REQUEST);
      CompletableFuture<SystemOneResponse> second = f.client.systemOneAsync(REQUEST);
      f.client.close();
      assertThat(first).isCancelled();
      assertThat(second).isCancelled();
      assertThat(f.executor.isShutdown()).isFalse();
      f.startWork();
      assertThat(f.executor.submit(() -> "still usable").get(5, TimeUnit.SECONDS))
          .isEqualTo("still usable");
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void lateResponseCannotOverwriteCancellation(Backend backend) throws Exception {
    try (Fixture f = new Fixture(backend, true)) {
      CompletableFuture<SystemOneResponse> result = f.client.systemOneAsync(REQUEST);
      f.startWork();
      await(f.responseEntered);
      assertThat(result.cancel(true)).isTrue();
      f.releaseResponse.countDown();
      await(f.responseFinished);
      f.drainWorker();
      assertThat(result).isCancelled();
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void blockedSuccessCallbackDoesNotHoldOtherResultsOrClose(Backend backend) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (Fixture f = new Fixture(backend, false)) {
      CompletableFuture<SystemOneResponse> first = f.client.systemOneAsync(REQUEST);
      CompletableFuture<Void> callback =
          first.thenRun(
              () -> {
                entered.countDown();
                await(release);
              });
      CompletableFuture<SystemOneResponse> second = f.client.systemOneAsync(REQUEST);
      try {
        f.startWork();
        await(entered);
        assertThat(second.get(5, TimeUnit.SECONDS).model()).isEqualTo(RESPONSE.model());
        f.client.close();
        assertThat(first).isDone();
        assertThat(callback).isNotDone();
      } finally {
        release.countDown();
      }
      callback.get(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void blockedCancellationCallbackDoesNotHoldClose(Backend backend) throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (Fixture f = new Fixture(backend, false)) {
      CompletableFuture<SystemOneResponse> first = f.client.systemOneAsync(REQUEST);
      CompletableFuture<Void> callback =
          first.handle(
              (r, t) -> {
                entered.countDown();
                await(release);
                return null;
              });
      CompletableFuture<SystemOneResponse> second = f.client.systemOneAsync(REQUEST);
      try {
        f.client.close();
        await(entered);
        assertThat(first).isCancelled();
        assertThat(second).isCancelled();
        assertThat(callback).isNotDone();
      } finally {
        release.countDown();
      }
      callback.get(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @EnumSource(Backend.class)
  void admissionRacingCloseIsEitherRejectedOrPublished(Backend backend) throws Exception {
    for (int i = 0; i < 10; i++) {
      try (Fixture f = new Fixture(backend, false);
          ExecutorService racers = Executors.newFixedThreadPool(2)) {
        CountDownLatch start = new CountDownLatch(1);
        var submitted =
            racers.submit(
                () -> {
                  await(start);
                  try {
                    return f.client.systemOneAsync(REQUEST);
                  } catch (IllegalStateException closed) {
                    return null;
                  }
                });
        var closed =
            racers.submit(
                () -> {
                  await(start);
                  f.client.close();
                });
        start.countDown();
        CompletableFuture<SystemOneResponse> result = submitted.get(5, TimeUnit.SECONDS);
        closed.get(5, TimeUnit.SECONDS);
        if (result != null) {
          assertThat(result).isCancelled();
        }
        assertThatThrownBy(() -> f.client.systemOneAsync(REQUEST))
            .isInstanceOf(IllegalStateException.class);
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).as("controlled checkpoint reached").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted at controlled checkpoint", e);
    }
  }

  /** Owns only test resources; all work begins behind an explicit queue gate. */
  private static final class Fixture implements AutoCloseable {
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final CountDownLatch queueGate = new CountDownLatch(1);
    final CountDownLatch responseEntered = new CountDownLatch(1);
    final CountDownLatch releaseResponse;
    final CountDownLatch responseFinished = new CountDownLatch(1);
    final JevClient client;
    private final HttpServer server;
    private final ExecutorService serverExecutor;

    Fixture(Backend backend, boolean holdResponse) throws IOException {
      releaseResponse = new CountDownLatch(holdResponse ? 1 : 0);
      executor.submit(() -> await(queueGate));
      if (backend == Backend.HTTP) {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext(
            "/v1/systemone",
            exchange -> {
              try (exchange) {
                exchange.getRequestBody().readAllBytes();
                respond();
                byte[] body = RESPONSE.rawBody().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
              } catch (IOException cancelled) {
                // A cancelled HTTP request can close the connection before this held response
                // writes.
              } finally {
                responseFinished.countDown();
              }
            });
        server.start();
        client =
            JevClient.builder()
                .apiKey("contract-test")
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .executor(executor)
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
      } else {
        server = null;
        serverExecutor = null;
        client =
            new RecordingJevClient(Clock.systemUTC(), executor)
                .respondWith(
                    request -> {
                      try {
                        return respond();
                      } finally {
                        responseFinished.countDown();
                      }
                    });
      }
    }

    private SystemOneResponse respond() {
      responseEntered.countDown();
      await(releaseResponse);
      return RESPONSE;
    }

    void startWork() {
      queueGate.countDown();
    }

    void drainWorker() throws Exception {
      executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
      queueGate.countDown();
      releaseResponse.countDown();
      try {
        client.close();
      } finally {
        executor.shutdownNow();
        if (server != null) {
          server.stop(0);
        }
        if (serverExecutor != null) {
          serverExecutor.shutdownNow();
        }
      }
    }
  }
}
