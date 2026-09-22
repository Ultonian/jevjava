package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import net.codefinch.jev.internal.Sleeper;

/** Explicit per-test transport and controllable delivery fixtures. */
final class HttpTestFixture implements AutoCloseable {
  static final String OK = Fixtures.read("responses/docs-all-three.json");
  static final String MODELS = Fixtures.read("responses/models.json");
  static final Questions QUESTIONS = Questions.of("q", NoulQuestion.of("?"));
  static final SystemOneRequest REQUEST = SystemOneRequest.of(State.of("s"), QUESTIONS);

  final TestServer server;

  /** Delays the client asked to sleep, without sleeping. */
  final List<Duration> sleeps = new ArrayList<>();

  private final Sleeper fakeSleeper =
      (delay, handle) -> {
        sleeps.add(delay);
        return !handle.isCancelled();
      };

  HttpTestFixture() throws IOException {
    server = new TestServer();
  }

  @Override
  public void close() {
    server.close();
  }

  JevClientBuilder client() {
    return JevClient.builder()
        .apiKey("test-key")
        .baseUrl(server.baseUrl())
        .sleeper(fakeSleeper)
        .random(
            new RandomGenerator() {
              @Override
              public long nextLong() {
                return 0; // nextDouble() -> 0.0: no jitter
              }
            });
  }

  void awaitRequests(int n) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (server.requests().size() < n && System.nanoTime() < end) {
      Thread.sleep(10);
    }
    assertThat(server.requests()).hasSizeGreaterThanOrEqualTo(n);
  }

  /** Holds publications until released, then runs each on a fresh virtual thread. */
  static final class HoldingDelivery implements java.util.concurrent.Executor {
    final List<Runnable> held = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean open;

    @Override
    public void execute(Runnable r) {
      if (open) {
        Thread.startVirtualThread(r);
      } else {
        held.add(r);
      }
    }

    void release() {
      open = true;
      held.forEach(Thread::startVirtualThread);
      held.clear();
    }
  }

  static ExecutorService blockedCallerExecutor(CountDownLatch occupied) {
    ExecutorService single = Executors.newSingleThreadExecutor(r -> new Thread(r, "caller-exec"));
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    return single;
  }

  static HttpClient callerHttp() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  }

  static void drain(ExecutorService executor) throws Exception {
    executor.submit(() -> {}).get(5, TimeUnit.SECONDS); // barrier: everything queued before has run
  }
}
