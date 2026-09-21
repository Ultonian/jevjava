package net.codefinch.jev.test;

import java.time.Clock;
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
import java.util.function.Function;
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
 * virtual thread each by default) and return at once; cancelling the returned future stops its
 * result from being published; calls after {@link #close()} throw {@link IllegalStateException};
 * and {@code close()} completes every outstanding future with {@link CancellationException}, so a
 * responder that finishes later is discarded. Thread-safe.
 */
public final class RecordingJevClient implements JevClient {
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
   * ({@code Runnable::run}) makes async calls complete synchronously, which some tests prefer.
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
    Function<SystemOneRequest, SystemOneResponse> responder = admitSystemOne(request, options);
    return responder.apply(request);
  }

  @Override
  public CompletableFuture<SystemOneResponse> systemOneAsync(
      SystemOneRequest request, RequestOptions options) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(options, "options");
    Function<SystemOneRequest, SystemOneResponse> responder = admitSystemOne(request, options);
    return runAsync(() -> responder.apply(request));
  }

  @Override
  public ModelList models(RequestOptions options) {
    Objects.requireNonNull(options, "options");
    admit(new RecordedCall("models", Optional.empty(), options, clock.instant()));
    return models;
  }

  @Override
  public CompletableFuture<ModelList> modelsAsync(RequestOptions options) {
    Objects.requireNonNull(options, "options");
    admit(new RecordedCall("models", Optional.empty(), options, clock.instant()));
    ModelList current = models;
    return runAsync(() -> current);
  }

  /**
   * Stops admission, then completes every outstanding future with {@link CancellationException}.
   */
  @Override
  public void close() {
    synchronized (lifecycle) {
      closed = true;
    }
    for (CompletableFuture<?> f : inFlight) {
      f.completeExceptionally(new CancellationException("client closed"));
    }
    inFlight.clear();
  }

  /** Whether {@link #close()} has been called. */
  public boolean isClosed() {
    synchronized (lifecycle) {
      return closed;
    }
  }

  private Function<SystemOneRequest, SystemOneResponse> admitSystemOne(
      SystemOneRequest request, RequestOptions options) {
    admit(new RecordedCall("systemone", Optional.of(request), options, clock.instant()));
    synchronized (script) {
      Function<SystemOneRequest, SystemOneResponse> next = script.pollFirst();
      return next != null ? next : defaultResponder;
    }
  }

  /** Records the call atomically with the closed check, like the HTTP client's admission. */
  private void admit(RecordedCall call) {
    synchronized (lifecycle) {
      if (closed) {
        throw new IllegalStateException("JevClient is closed");
      }
      calls.add(call);
    }
  }

  private <T> CompletableFuture<T> runAsync(java.util.function.Supplier<T> work) {
    CompletableFuture<T> future = new CompletableFuture<>();
    inFlight.add(future);
    future.whenComplete((r, t) -> inFlight.remove(future));
    executor.execute(
        () -> {
          if (future.isDone()) {
            return; // cancelled or closed before it started
          }
          try {
            T result = work.get();
            future.complete(result); // a no-op if cancelled or closed meanwhile: result discarded
          } catch (Throwable e) {
            future.completeExceptionally(e);
          }
        });
    return future;
  }
}
