package net.codefinch.jev.test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.function.Supplier;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevException;
import net.codefinch.jev.ModelList;
import net.codefinch.jev.ModelMetadata;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.ResponseMetadata;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;

/**
 * An in-memory {@link JevClient} for tests. Each {@code systemOne} call is answered, in order, by
 * the next scripted response, exception, or responder; when the script is empty, by the default
 * responder ({@link ScriptedAnswers#neutralResponse(net.codefinch.jev.Questions)} unless replaced).
 * Every call is recorded with its request and options.
 *
 * <p>Lifecycle follows the {@link JevClient} contract the HTTP client implements: synchronous calls
 * run on the caller's thread; asynchronous calls run their responder on an executor (a fresh
 * virtual thread each by default) and return at once, registered atomically with admission;
 * completions — results and cancellations alike — are published on a fresh virtual thread, never on
 * the responder executor or the closing thread; cancelling the returned future stops its result
 * from being published; calls after {@link #close()} throw {@link IllegalStateException}; and
 * {@code close()} completes every outstanding future with {@link CancellationException}, waiting
 * only for publication (never for continuations), so a responder that finishes later is discarded.
 * Thread-safe.
 */
public final class RecordingJevClient implements JevClient {

  /**
   * How long {@link #close()} waits for cancellations to be published (never for continuations).
   */
  static final Duration PUBLICATION_WAIT = Duration.ofSeconds(5);

  private final Deque<Function<SystemOneRequest, SystemOneResponse>> script = new ArrayDeque<>();
  private final List<RecordedCall> calls = new CopyOnWriteArrayList<>();
  private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
  private final Clock clock;
  private final Executor executor;
  private final Object lifecycle = new Object();
  private boolean closed; // guarded by lifecycle
  private volatile Function<SystemOneRequest, SystemOneResponse> defaultResponder =
      request -> ScriptedAnswers.neutralResponse(request.questions());
  private volatile ModelList models = defaultModels();

  /** A client that records with the system clock and runs async calls on virtual threads. */
  public RecordingJevClient() {
    this(Clock.systemUTC(), Thread::startVirtualThread);
  }

  /** A client with the given clock for timestamps. */
  public RecordingJevClient(Clock clock) {
    this(clock, Thread::startVirtualThread);
  }

  /**
   * A client with the given clock and the executor async responders run on. A same-thread executor
   * ({@code Runnable::run}) runs the responder before {@code systemOneAsync} returns; the result is
   * still published on a virtual thread.
   */
  public RecordingJevClient(Clock clock, Executor executor) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  private static ModelList defaultModels() {
    List<ModelMetadata> list =
        List.of(new ModelMetadata("jev-latest", "Recording fake", "2026-01-01"));
    return new ModelList(list, ResponseMetadata.of(Map.of(), WireJson.models(list)));
  }

  // ---- scripting ------------------------------------------------------------------------------

  /** Queues a response for the next {@code systemOne} call. */
  public RecordingJevClient enqueue(SystemOneResponse response) {
    Objects.requireNonNull(response, "response");
    return enqueue(request -> response);
  }

  /** Queues a script built from answers. */
  public RecordingJevClient enqueue(ScriptedAnswers answers) {
    Objects.requireNonNull(answers, "answers");
    return enqueue(answers.build());
  }

  /** Queues a responder computed from the request. */
  public RecordingJevClient enqueue(Function<SystemOneRequest, SystemOneResponse> responder) {
    Objects.requireNonNull(responder, "responder");
    synchronized (script) {
      script.add(responder);
    }
    return this;
  }

  /** Queues a failure for the next {@code systemOne} call. */
  public RecordingJevClient enqueueFailure(JevException failure) {
    Objects.requireNonNull(failure, "failure");
    return enqueue(
        request -> {
          throw failure;
        });
  }

  /** Replaces the responder used when the script is empty. */
  public RecordingJevClient respondWith(Function<SystemOneRequest, SystemOneResponse> responder) {
    this.defaultResponder = Objects.requireNonNull(responder, "responder");
    return this;
  }

  /** Replaces the {@code models()} response with a consistent typed list and wire body. */
  public RecordingJevClient modelsResponse(List<ModelMetadata> models) {
    List<ModelMetadata> copy = List.copyOf(models);
    this.models = new ModelList(copy, ResponseMetadata.of(Map.of(), WireJson.models(copy)));
    return this;
  }

  /** Replaces the {@code models()} response as given. */
  public RecordingJevClient modelsResponse(ModelList models) {
    this.models = Objects.requireNonNull(models, "models");
    return this;
  }

  // ---- recording ------------------------------------------------------------------------------

  /** Every call so far, in order. Unmodifiable snapshot. */
  public List<RecordedCall> calls() {
    return List.copyOf(calls);
  }

  /** The {@code systemone} requests so far, in order. */
  public List<SystemOneRequest> requests() {
    return List.copyOf(calls).stream().flatMap(c -> c.request().stream()).toList();
  }

  /** The last call, if any. Reads one consistent snapshot. */
  public Optional<RecordedCall> lastCall() {
    List<RecordedCall> snapshot = List.copyOf(calls);
    return snapshot.isEmpty() ? Optional.empty() : Optional.of(snapshot.get(snapshot.size() - 1));
  }

  /** Forgets recorded calls (the script is untouched). */
  public RecordingJevClient reset() {
    calls.clear();
    return this;
  }

  /** Number of scripted responses not yet consumed. */
  public int pendingScript() {
    synchronized (script) {
      return script.size();
    }
  }

  // ---- JevClient ------------------------------------------------------------------------------

  @Override
  public SystemOneResponse systemOne(SystemOneRequest request, RequestOptions options) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(options, "options");
    return admitSystemOne(request, options, null).apply(request);
  }

  @Override
  public CompletableFuture<SystemOneResponse> systemOneAsync(
      SystemOneRequest request, RequestOptions options) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(options, "options");
    CompletableFuture<SystemOneResponse> future = new CompletableFuture<>();
    Function<SystemOneRequest, SystemOneResponse> responder =
        admitSystemOne(request, options, future);
    return runAsync(future, () -> responder.apply(request));
  }

  @Override
  public ModelList models(RequestOptions options) {
    Objects.requireNonNull(options, "options");
    admit(new RecordedCall("models", Optional.empty(), options, clock.instant()), null);
    return models;
  }

  @Override
  public CompletableFuture<ModelList> modelsAsync(RequestOptions options) {
    Objects.requireNonNull(options, "options");
    CompletableFuture<ModelList> future = new CompletableFuture<>();
    admit(new RecordedCall("models", Optional.empty(), options, clock.instant()), future);
    ModelList current = models;
    return runAsync(future, () -> current);
  }

  /**
   * Stops admission (atomically with registration), publishes a {@link CancellationException} into
   * every outstanding future on a fresh virtual thread each, and returns once each is done — never
   * waiting for application continuations. Throws {@link JevException} if publication does not
   * complete within {@link #PUBLICATION_WAIT}, so an unmet guarantee is observable.
   */
  @Override
  public void close() {
    List<CompletableFuture<?>> outstanding;
    synchronized (lifecycle) {
      closed = true;
      outstanding = List.copyOf(inFlight);
    }
    for (CompletableFuture<?> f : outstanding) {
      publish(f, () -> f.completeExceptionally(new CancellationException("client closed")));
    }
    long until = System.nanoTime() + PUBLICATION_WAIT.toNanos();
    for (CompletableFuture<?> f : outstanding) {
      while (!f.isDone()) {
        if (System.nanoTime() >= until) {
          throw new JevException(
              "close(): a result was still unpublished after " + PUBLICATION_WAIT);
        }
        // Park rather than spin: a spinning virtual thread would keep its carrier and could starve
        // the very publication threads it is waiting for.
        LockSupport.parkNanos(1_000_000L);
        if (Thread.interrupted()) {
          Thread.currentThread().interrupt();
          throw new JevException("close(): interrupted while waiting for publication");
        }
      }
    }
  }

  /** Whether {@link #close()} has been called. */
  public boolean isClosed() {
    synchronized (lifecycle) {
      return closed;
    }
  }

  private Function<SystemOneRequest, SystemOneResponse> admitSystemOne(
      SystemOneRequest request, RequestOptions options, CompletableFuture<?> future) {
    admit(new RecordedCall("systemone", Optional.of(request), options, clock.instant()), future);
    synchronized (script) {
      Function<SystemOneRequest, SystemOneResponse> next = script.pollFirst();
      return next != null ? next : defaultResponder;
    }
  }

  /**
   * Records the call and registers its future atomically with the closed check, like the HTTP
   * client's admission: a call is either rejected or visible to {@link #close()}, never in between.
   */
  private void admit(RecordedCall call, CompletableFuture<?> future) {
    synchronized (lifecycle) {
      if (closed) {
        throw new IllegalStateException("JevClient is closed");
      }
      calls.add(call);
      if (future != null) {
        inFlight.add(future);
        future.whenComplete((r, t) -> inFlight.remove(future));
      }
    }
  }

  private <T> CompletableFuture<T> runAsync(CompletableFuture<T> future, Supplier<T> work) {
    try {
      executor.execute(
          () -> {
            if (future.isDone()) {
              return; // cancelled or closed before it started
            }
            T result;
            try {
              result = work.get();
            } catch (Throwable e) {
              publish(future, () -> future.completeExceptionally(e));
              return;
            }
            publish(future, () -> future.complete(result)); // no-op if cancelled/closed meanwhile
          });
    } catch (RuntimeException rejected) {
      publish(future, () -> future.completeExceptionally(rejected));
    }
    return future;
  }

  /** Publishes a completion on a fresh virtual thread: continuations never run on the caller. */
  private static void publish(CompletableFuture<?> future, Runnable completion) {
    if (future.isDone()) {
      return;
    }
    Thread.startVirtualThread(completion);
  }
}
