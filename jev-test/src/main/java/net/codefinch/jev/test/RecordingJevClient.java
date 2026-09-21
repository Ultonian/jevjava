package net.codefinch.jev.test;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * responder ({@link ScriptedAnswers#neutralResponse(Questions)} unless replaced). Every call is
 * recorded with its request and options.
 *
 * <p>Thread-safe. Async methods complete immediately on the calling thread. {@link #close()}
 * follows the interface contract: later calls throw {@link IllegalStateException}.
 */
public final class RecordingJevClient implements JevClient {
  private final Deque<Function<SystemOneRequest, SystemOneResponse>> script = new ArrayDeque<>();
  private final List<RecordedCall> calls = new CopyOnWriteArrayList<>();
  private final Clock clock;
  private volatile Function<SystemOneRequest, SystemOneResponse> defaultResponder =
      request -> ScriptedAnswers.neutralResponse(request.questions());
  private volatile ModelList models =
      new ModelList(
          List.of(new ModelMetadata("jev-latest", "Recording fake", "2026-01-01")),
          ResponseMetadata.of(java.util.Map.of(), "{\"models\":[]}"));
  private volatile boolean closed;

  /** A client that records with the system clock. */
  public RecordingJevClient() {
    this(Clock.systemUTC());
  }

  /** A client that timestamps recorded calls with the given clock. */
  public RecordingJevClient(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // ---- scripting ------------------------------------------------------------------------------

  /** Queues a response for the next {@code systemOne} call. */
  public RecordingJevClient enqueue(SystemOneResponse response) {
    Objects.requireNonNull(response, "response");
    synchronized (script) {
      script.add(request -> response);
    }
    return this;
  }

  /** Queues a script built from answers; the response is built when the call arrives. */
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
    synchronized (script) {
      script.add(
          request -> {
            throw failure;
          });
    }
    return this;
  }

  /** Replaces the responder used when the script is empty. */
  public RecordingJevClient respondWith(Function<SystemOneRequest, SystemOneResponse> responder) {
    this.defaultResponder = Objects.requireNonNull(responder, "responder");
    return this;
  }

  /** Replaces the {@code models()} response. */
  public RecordingJevClient modelsResponse(ModelList models) {
    this.models = Objects.requireNonNull(models, "models");
    return this;
  }

  // ---- recording ------------------------------------------------------------------------------

  /** Every call so far, in order. Unmodifiable snapshot. */
  public List<RecordedCall> calls() {
    return Collections.unmodifiableList(List.copyOf(calls));
  }

  /** The {@code systemone} requests so far, in order. */
  public List<SystemOneRequest> requests() {
    return calls.stream().flatMap(c -> c.request().stream()).toList();
  }

  /** The last call, if any. */
  public Optional<RecordedCall> lastCall() {
    return calls.isEmpty() ? Optional.empty() : Optional.of(calls.get(calls.size() - 1));
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
    requireOpen();
    calls.add(new RecordedCall("systemone", Optional.of(request), options, clock.instant()));
    Function<SystemOneRequest, SystemOneResponse> responder;
    synchronized (script) {
      responder = script.pollFirst();
    }
    return (responder != null ? responder : defaultResponder).apply(request);
  }

  @Override
  public CompletableFuture<SystemOneResponse> systemOneAsync(
      SystemOneRequest request, RequestOptions options) {
    try {
      return CompletableFuture.completedFuture(systemOne(request, options));
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public ModelList models(RequestOptions options) {
    Objects.requireNonNull(options, "options");
    requireOpen();
    calls.add(new RecordedCall("models", Optional.empty(), options, clock.instant()));
    return models;
  }

  @Override
  public CompletableFuture<ModelList> modelsAsync(RequestOptions options) {
    try {
      return CompletableFuture.completedFuture(models(options));
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void close() {
    closed = true;
  }

  /** Whether {@link #close()} has been called. */
  public boolean isClosed() {
    return closed;
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("JevClient is closed");
    }
  }
}
