package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HoldingDeliveryTest {
  @ParameterizedTest(name = "pause before clear: {0}")
  @ValueSource(booleans = {false, true})
  void enqueueRacingReleaseRunsEveryTaskExactlyOnce(boolean pauseClear) throws Exception {
    PausingQueue queue = new PausingQueue(pauseClear);
    TrackingLauncher launcher = new TrackingLauncher();
    HttpTestFixture.HoldingDelivery delivery = new HttpTestFixture.HoldingDelivery(queue, launcher);
    AtomicInteger earlyRuns = new AtomicInteger();
    AtomicInteger lateRuns = new AtomicInteger();
    delivery.execute(earlyRuns::incrementAndGet);
    queue.pauseAdd = true;
    Task producer = Task.start(() -> delivery.execute(lateRuns::incrementAndGet));
    Task releaser = null;
    try {
      await(queue.enteredAdd);
      releaser = Task.start(delivery::release);
      // The old implementation can finish release (or reach clear) while enqueue is paused.
      // Atomic admission instead blocks the releaser until the pending enqueue has committed.
      awaitReleaseCheckpoint(releaser, pauseClear ? queue.enteredClear : null);
      queue.allowAdd.countDown();
      producer.finish();
      if (pauseClear) {
        await(queue.enteredClear);
        queue.allowClear.countDown();
      }
      releaser.finish();
      launcher.joinAll();
      assertThat(earlyRuns.get()).isOne();
      assertThat(lateRuns.get()).isOne();
      assertThat(queue).isEmpty();
      delivery.release();
      launcher.joinAll();
      assertThat(earlyRuns.get()).as("repeated release cannot duplicate the early task").isOne();
      assertThat(lateRuns.get()).as("repeated release cannot duplicate the late task").isOne();
    } finally {
      queue.allowAdd.countDown();
      queue.allowClear.countDown();
      producer.finish();
      if (releaser != null) {
        releaser.finish();
      }
      launcher.joinAll();
    }
  }

  @Test
  void concurrentReleaseAndSubmissionProceedWhileAnEarlierLaunchIsHeld() throws Exception {
    TrackingLauncher launcher = new TrackingLauncher();
    CountDownLatch enteredLaunch = new CountDownLatch(1);
    CountDownLatch allowLaunch = new CountDownLatch(1);
    AtomicInteger launches = new AtomicInteger();
    HttpTestFixture.HoldingDelivery delivery =
        new HttpTestFixture.HoldingDelivery(
            new CopyOnWriteArrayList<>(),
            task -> {
              if (launches.incrementAndGet() == 1) {
                enteredLaunch.countDown();
                await(allowLaunch);
              }
              launcher.accept(task);
            });
    AtomicInteger queuedRuns = new AtomicInteger();
    AtomicInteger openRuns = new AtomicInteger();
    delivery.execute(queuedRuns::incrementAndGet);
    Task firstRelease = Task.start(delivery::release);
    Task otherRelease = null;
    Task producer = null;
    try {
      await(enteredLaunch);
      otherRelease = Task.start(delivery::release);
      otherRelease.finish();
      producer = Task.start(() -> delivery.execute(openRuns::incrementAndGet));
      producer.finish();
      launcher.joinAll();
      assertThat(openRuns.get())
          .as("open delivery progresses while an earlier launch is held")
          .isOne();
      allowLaunch.countDown();
      firstRelease.finish();
      launcher.joinAll();
      assertThat(queuedRuns.get()).as("concurrent releases cannot duplicate a queued task").isOne();
      assertThat(launches.get()).isEqualTo(2);
      assertThat(delivery.held).isEmpty();
    } finally {
      allowLaunch.countDown();
      firstRelease.finish();
      if (otherRelease != null) {
        otherRelease.finish();
      }
      if (producer != null) {
        producer.finish();
      }
      launcher.joinAll();
    }
  }

  private static void await(CountDownLatch gate) {
    try {
      assertThat(gate.await(5, TimeUnit.SECONDS)).as("controlled checkpoint reached").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted at controlled checkpoint", e);
    }
  }

  private static void awaitReleaseCheckpoint(Task task, CountDownLatch enteredClear)
      throws InterruptedException {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!atReleaseCheckpoint(task, enteredClear) && System.nanoTime() < until) {
      Thread.sleep(1);
    }
    assertThat(atReleaseCheckpoint(task, enteredClear))
        .as("release has completed, reached clear, or blocked behind the pending enqueue")
        .isTrue();
  }

  private static boolean atReleaseCheckpoint(Task task, CountDownLatch enteredClear) {
    return task.result.isDone()
        || task.thread.getState() == Thread.State.BLOCKED
        || (enteredClear != null && enteredClear.getCount() == 0);
  }

  private record Task(FutureTask<Void> result, Thread thread) {
    static Task start(Runnable work) {
      FutureTask<Void> result = new FutureTask<>(work, null);
      return new Task(result, Thread.ofPlatform().start(result));
    }

    void finish() throws Exception {
      result.get(5, TimeUnit.SECONDS);
      thread.join(5000);
      assertThat(thread.isAlive()).isFalse();
    }
  }

  private static final class TrackingLauncher implements Consumer<Runnable> {
    private final List<Thread> threads = new CopyOnWriteArrayList<>();

    @Override
    public void accept(Runnable task) {
      threads.add(Thread.startVirtualThread(task));
    }

    void joinAll() throws InterruptedException {
      for (Thread thread : threads) {
        thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(thread.isVirtual()).isTrue();
      }
    }
  }

  private static final class PausingQueue extends CopyOnWriteArrayList<Runnable> {
    private static final long serialVersionUID = 1L;
    private final boolean pauseClear;
    private final transient CountDownLatch enteredAdd = new CountDownLatch(1);
    private final transient CountDownLatch allowAdd = new CountDownLatch(1);
    private final transient CountDownLatch enteredClear = new CountDownLatch(1);
    private final transient CountDownLatch allowClear = new CountDownLatch(1);
    private volatile boolean pauseAdd;

    PausingQueue(boolean pauseClear) {
      this.pauseClear = pauseClear;
    }

    @Override
    public boolean add(Runnable task) {
      if (pauseAdd) {
        enteredAdd.countDown();
        await(allowAdd);
      }
      return super.add(task);
    }

    @Override
    public void clear() {
      if (pauseClear) {
        enteredClear.countDown();
        await(allowClear);
      }
      super.clear();
    }
  }
}
