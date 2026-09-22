package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpObserverLifecycleTest {
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

  /**
   * Holds the worker at its very first clock read (the attempt-start timestamp, or the deadline
   * check just before it) while a terminal path fires. Either the attempt never starts, or its
   * event precedes the terminal one; the terminal event never comes first.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"cancel", "close", "deadline"})
  void terminalEventNeverPrecedesAnAttemptThatStarted(String path) throws Exception {
    server.enqueueJson(200, OK);
    Thread testThread = Thread.currentThread();
    CountDownLatch workerHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Boolean> armed = new AtomicReference<>(false);
    java.util.function.LongSupplier holdingClock =
        () -> {
          Thread t = Thread.currentThread();
          if (t != testThread
              && !t.getName().equals("jev-deadline")
              && armed.compareAndSet(true, false)) {
            workerHeld.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              t.interrupt();
            }
          }
          return System.nanoTime();
        };
    List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        client().nanoTime(holdingClock).observer(ordering).closeGracePeriod(Duration.ZERO);
    JevClient c =
        (path.equals("deadline") ? b.deadline(Duration.ofMillis(200)) : b.noDeadline()).build();
    armed.set(true);
    CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
    assertThat(workerHeld.await(3, TimeUnit.SECONDS))
        .as("worker held at its first clock read")
        .isTrue();
    switch (path) {
      case "cancel" -> f.cancel(true);
      case "close" -> c.close();
      default -> Thread.sleep(300); // let the deadline expire while the worker is held
    }
    assertThat(f.isDone()).as("the public result is terminal while the worker is held").isTrue();
    release.countDown();
    assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(100); // any late attempt event would arrive now
    String expected = path.equals("deadline") ? "call:DEADLINE" : "call:CANCELLED";
    assertThat(order).as(path).isIn(List.of(expected), List.of("attempt1", expected));
    c.close();
  }
}
