package net.codefinch.jev.internal;

import java.lang.System.Logger.Level;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.JevApiException;
import net.codefinch.jev.JevDeadlineExceededException;
import net.codefinch.jev.JevException;
import net.codefinch.jev.JevInterruptedException;
import net.codefinch.jev.JevRateLimitException;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.RetryPolicy;
import net.codefinch.jev.SystemOneResponse;

/**
 * One admitted operation: retry/deadline policy, atomic attempt and terminal decisions,
 * cancellation, observer ordering and result publication. Client admission and resource shutdown
 * belong to {@link HttpJevClient}; one network exchange belongs to {@link HttpExchange}.
 */
final class CallExecution<T> {
  private final ClientConfig config;
  private final Diagnostics diagnostics;
  private final HttpExchange exchange;
  private final ScheduledThreadPoolExecutor scheduler;
  private final Consumer<CallExecution<?>> release;

  private final CallHandle handle = new CallHandle();
  private final CallSpec<T> spec;
  private final RequestOptions options;
  private final RetryPolicy retry;
  private final Duration attemptTimeout;
  private final Optional<Duration> deadline;
  private final long submittedAt;
  private final Optional<Long> deadlineAt;
  private final CallFuture<T> future; // null for synchronous calls
  private final AtomicBoolean outcomeClaimed = new AtomicBoolean();
  private volatile ScheduledFuture<?> timer;
  private volatile int attempts;
  private volatile JevException last;
  private volatile int lastStatus = -1;
  private CompletableFuture<Void> observerChain = CompletableFuture.completedFuture(null);

  CallExecution(
      CallSpec<T> spec,
      RequestOptions options,
      boolean asynchronous,
      ClientConfig config,
      Diagnostics diagnostics,
      HttpExchange exchange,
      ScheduledThreadPoolExecutor scheduler,
      Consumer<CallExecution<?>> release) {
    this.config = config;
    this.diagnostics = diagnostics;
    this.exchange = exchange;
    this.scheduler = scheduler;
    this.release = release;
    this.spec = spec;
    this.options = options;
    this.future = asynchronous ? new CallFuture<>() : null;
    this.retry = options.resolveRetry(config.retry());
    this.attemptTimeout = options.timeout().orElse(config.timeout());
    this.deadline =
        options.deadlineDisabled() ? Optional.empty() : options.deadline().or(config::deadline);
    this.submittedAt = config.nanoTime().getAsLong();
    this.deadlineAt = deadline.map(d -> submittedAt + d.toNanos());
    if (future != null) {
      future.call = this;
    }
  }

  T runSync() {
    T result;
    try {
      result = executeAttempts();
    } catch (Throwable t) {
      if (claimOutcome()) {
        observe(outcomeOf(t), null, t);
      }
      releaseTracking(); // sync: the throw is the publication
      throw t;
    }
    if (claimOutcome()) {
      observe(CallObserver.Outcome.SUCCESS, result, null);
    }
    releaseTracking(); // sync: the return is the publication
    return result;
  }

  CompletableFuture<T> runAsync() {
    armDeadline();
    try {
      config
          .executor()
          .execute(
              () -> {
                if (handle.isCancelled() || hasClaimedOutcome()) {
                  return; // cancelled, expired or closed while queued
                }
                T result;
                try {
                  result = executeAttempts();
                } catch (Throwable t) {
                  if (claimOutcome()) {
                    observe(outcomeOf(t), null, t);
                    publishResult(() -> future.completeExceptionally(t));
                  }
                  return;
                }
                if (claimOutcome()) {
                  observe(CallObserver.Outcome.SUCCESS, result, null);
                  publishResult(() -> future.complete(result));
                }
              });
    } catch (RejectedExecutionException e) {
      if (claimOutcome()) {
        handle.cancel();
        JevException failure = new JevException("executor rejected the call", e);
        observe(CallObserver.Outcome.ERROR, null, failure);
        publishResult(() -> future.completeExceptionally(failure));
      }
    }
    return future;
  }

  /** Schedules expiry of an async call independently of its executor. */
  private void armDeadline() {
    deadlineAt.ifPresent(
        at -> {
          long delay = Math.max(0, at - config.nanoTime().getAsLong());
          try {
            timer = scheduler.schedule(this::expire, delay, TimeUnit.NANOSECONDS);
            if (outcomeClaimed.get()) {
              timer.cancel(false); // outcome claimed before the timer was stored
            }
          } catch (RejectedExecutionException e) {
            cancelFromClient(); // scheduler is down: the client closed under us
          }
        });
  }

  /**
   * Claims the terminal state. Exactly one caller wins and must then deliver the outcome. Winning
   * disarms the timer and never touches application callbacks. The call stays tracked until the
   * outcome is published, so {@link HttpJevClient#close()} can still see it in the hand-off gap.
   */
  private boolean claimOutcome() {
    synchronized (
        this) { // atomic with startAttempt(): termination and attempt start never interleave
      if (!outcomeClaimed.compareAndSet(false, true)) {
        return false;
      }
    }
    ScheduledFuture<?> t = timer;
    if (t != null) {
      t.cancel(false);
    }
    return true;
  }

  /**
   * Decides, atomically with any terminal transition, that this attempt starts: if the call has
   * already claimed an outcome the attempt never starts; otherwise its observer slot is reserved
   * before any terminal event can be enqueued, so attempts always precede the call event.
   */
  private CompletableFuture<Runnable> startAttempt(int attempt) {
    synchronized (this) {
      if (outcomeClaimed.get() || handle.isCancelled()) {
        throw cancelled(null);
      }
      attempts = attempt + 1;
      return config.observers().isEmpty() ? null : reserveObserverSlot();
    }
  }

  boolean hasUnpublishedResult() {
    return future != null && !future.isDone();
  }

  boolean hasClaimedOutcome() {
    return outcomeClaimed.get();
  }

  /** The outcome is visible on the public result; stop tracking. Idempotent. */
  private void releaseTracking() {
    release.accept(this);
  }

  /**
   * Publishes a completion on a delivery thread, never on the caller of this method. The default
   * delivery starts a fresh virtual thread and cannot reject; an injected delivery that does is
   * backed up by one, so no completion ever runs inline on a control thread.
   */
  private void publishResult(Runnable completion) {
    safeDelivery(
        () -> {
          try {
            completion.run();
          } finally {
            releaseTracking();
          }
        });
  }

  /** Runs on the delivery executor, falling back to a fresh virtual thread if it rejects. */
  private void safeDelivery(Runnable task) {
    try {
      config.delivery().execute(task);
    } catch (RejectedExecutionException e) {
      Thread.startVirtualThread(task);
    }
  }

  /**
   * Queues an observer event behind this call's earlier events (and behind any reserved attempt
   * slot), on delivery threads: never on the deadline timer, the closing thread, the operation
   * thread or the caller of {@code cancel()}.
   */
  private void dispatchToObservers(Runnable dispatch) {
    CompletableFuture<Runnable> slot = reserveObserverSlot();
    slot.complete(dispatch);
  }

  /**
   * Reserves the next position in this call's event sequence. An attempt reserves its slot when it
   * starts and fills it when it ends, so a terminal event enqueued while the attempt is still
   * running is delivered after it — without any control thread waiting for the worker.
   */
  private CompletableFuture<Runnable> reserveObserverSlot() {
    CompletableFuture<Runnable> slot = new CompletableFuture<>();
    synchronized (this) {
      observerChain =
          observerChain
              .thenCompose(v -> slot)
              .thenAcceptAsync(Runnable::run, this::safeDelivery)
              .exceptionally(t -> null);
    }
    return slot;
  }

  /** The deadline passed while the call was queued or running: cancel first, then deliver. */
  private void expire() {
    if (claimOutcome()) {
      handle.cancel();
      JevDeadlineExceededException failure = deadlineExceeded(attempts, last);
      observe(CallObserver.Outcome.DEADLINE, null, failure);
      publishResult(() -> future.completeExceptionally(failure));
    }
  }

  /** {@link HttpJevClient#close()} reached this call before it finished. */
  void cancelFromClient() {
    if (claimOutcome()) {
      handle.cancel();
      observe(CallObserver.Outcome.CANCELLED, null, null);
      if (future != null) {
        CancellationException cancelled =
            new CancellationException(spec.endpoint() + ": client closed");
        publishResult(() -> future.completeExceptionally(cancelled));
      }
    }
  }

  /**
   * Emits the call event to every observer; exactly one per call, from the claimOutcome() winner.
   */
  private void observe(CallObserver.Outcome outcome, Object result, Throwable failure) {
    if (config.observers().isEmpty()) {
      return;
    }
    SystemOneResponse response = result instanceof SystemOneResponse r ? r : null;
    CallObserver.Call event =
        new CallObserver.Call(
            spec.operation(),
            outcome,
            attempts,
            Duration.ofNanos(config.nanoTime().getAsLong() - submittedAt),
            lastStatus < 0 ? OptionalInt.empty() : OptionalInt.of(lastStatus),
            Optional.ofNullable(response).map(SystemOneResponse::model),
            Optional.ofNullable(response),
            Optional.ofNullable(failure));
    dispatchToObservers(
        () -> {
          for (CallObserver observer : config.observers()) {
            try {
              observer.onCall(event);
            } catch (RuntimeException e) {
              diagnostics.log(Level.WARNING, () -> "CallObserver.onCall threw; ignoring", e);
            }
          }
        });
  }

  private void observeAttempt(
      CompletableFuture<Runnable> slot, int attempt, long started, int status, Throwable failure) {
    if (slot == null) {
      return;
    }
    CallObserver.Attempt event =
        new CallObserver.Attempt(
            spec.operation(),
            attempt + 1,
            Duration.ofNanos(config.nanoTime().getAsLong() - started),
            status < 0 ? OptionalInt.empty() : OptionalInt.of(status),
            Optional.ofNullable(failure));
    slot.complete(
        () -> {
          for (CallObserver observer : config.observers()) {
            try {
              observer.onAttempt(event);
            } catch (RuntimeException e) {
              diagnostics.log(Level.WARNING, () -> "CallObserver.onAttempt threw; ignoring", e);
            }
          }
        });
  }

  private T executeAttempts() {
    for (int attempt = 0; ; attempt++) {
      checkCancelled();
      Duration budget = attemptTimeout;
      if (deadlineAt.isPresent()) {
        long remaining = deadlineAt.get() - config.nanoTime().getAsLong();
        if (remaining <= 0) {
          throw deadlineExceeded(attempt, last);
        }
        budget = min(budget, Duration.ofNanos(remaining));
      }
      final CompletableFuture<Runnable> slot = startAttempt(attempt);
      try {
        return attempt(attempt, budget, slot);
      } catch (JevException e) {
        last = e;
        checkCancelled();
        if (e instanceof JevInterruptedException) {
          throw e;
        }
        if (deadlineAt.isPresent() && deadlineAt.get() - config.nanoTime().getAsLong() <= 0) {
          throw deadlineExceeded(attempt + 1, e);
        }
        if (attempt >= retry.maxRetries() || !retry.isRetryable(e)) {
          throw e;
        }
        Duration delay = retry.delay(attempt, serverDelay(e), config.random());
        if (deadlineAt.isPresent()
            && config.nanoTime().getAsLong() + delay.toNanos() >= deadlineAt.get()) {
          throw deadlineExceeded(attempt + 1, e);
        }
        final int attemptNumber = attempts;
        final int retriesTotal = retry.maxRetries();
        log(
            Level.INFO,
            () ->
                spec.endpoint()
                    + " retrying in "
                    + delay.toMillis()
                    + "ms (retry "
                    + attemptNumber
                    + "/"
                    + retriesTotal
                    + ") after "
                    + describe(e));
        log(
            Level.DEBUG,
            () -> spec.endpoint() + " attempt " + attemptNumber + " failure: " + e.getMessage());
        sleep(delay);
      }
    }
  }

  /**
   * One attempt, owning the slot {@link #startAttempt} reserved: every exit fills it, including a
   * failure while building the request (no exchange happened, so the event has no status), so a
   * later terminal event is never stuck behind an empty slot.
   */
  private T attempt(int attempt, Duration budget, CompletableFuture<Runnable> slot) {
    final long started = config.nanoTime().getAsLong();
    int status = -1;
    Throwable failure = null;
    try {
      HttpResponse<String> response = exchange.execute(spec, options, handle, attempt, budget);
      status = response.statusCode();
      lastStatus = status;
      return handle(response, started);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      observeAttempt(slot, attempt, started, status, failure);
    }
  }

  private T handle(HttpResponse<String> response, long started) {
    Map<String, List<String>> headers = response.headers().map();
    String body = response.body();
    long elapsedMs = (config.nanoTime().getAsLong() - started) / 1_000_000;
    log(
        Level.INFO,
        () ->
            spec.endpoint()
                + " -> "
                + response.statusCode()
                + " in "
                + elapsedMs
                + " ms request_id="
                + firstHeader(headers, "x-typesafe-request-id").orElse("-"));
    log(Level.DEBUG, () -> "<- headers " + Redaction.headers(headers) + " body " + body);
    int status = response.statusCode();
    if (status >= 200 && status < 300) {
      return spec.parser().parse(status, headers, body, spec.endpoint());
    }
    throw JevApiException.fromStatus(status, headers, body, spec.endpoint());
  }

  private void sleep(Duration delay) {
    try {
      if (!config.sleeper().sleep(delay, handle)) {
        throw cancelled(null);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JevInterruptedException(spec.endpoint() + ": interrupted during backoff", e);
    }
  }

  private void checkCancelled() {
    if (handle.isCancelled()) {
      throw cancelled(null);
    }
  }

  private CancellationException cancelled(Throwable cause) {
    CancellationException e = new CancellationException(spec.endpoint() + ": call cancelled");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }

  private JevDeadlineExceededException deadlineExceeded(int attempts, JevException last) {
    return new JevDeadlineExceededException(
        spec.endpoint()
            + ": operation deadline of "
            + deadline.map(Duration::toMillis).orElse(0L)
            + " ms exceeded after "
            + attempts
            + " attempt(s)",
        last);
  }

  /** Class and status only: exception messages can embed server body text. */
  private static String describe(JevException e) {
    return e instanceof JevApiException api
        ? e.getClass().getSimpleName() + " status=" + api.status()
        : e.getClass().getSimpleName();
  }

  private Optional<Duration> serverDelay(JevException e) {
    if (e instanceof JevRateLimitException limited) {
      return limited.retryAfter();
    }
    if (e instanceof JevApiException api) {
      return RetryAfter.parse(api.headers());
    }
    return Optional.empty();
  }

  private static CallObserver.Outcome outcomeOf(Throwable t) {
    if (t instanceof JevDeadlineExceededException) {
      return CallObserver.Outcome.DEADLINE;
    }
    if (t instanceof CancellationException || t instanceof JevInterruptedException) {
      return CallObserver.Outcome.CANCELLED;
    }
    return CallObserver.Outcome.ERROR;
  }

  /** A {@link CompletableFuture} whose cancellation reaches the running call first. */
  private static final class CallFuture<T> extends CompletableFuture<T> {
    private volatile CallExecution<T> call;

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      CallExecution<T> c = call;
      if (c == null || !c.claimOutcome()) {
        return super.cancel(mayInterruptIfRunning); // already terminal: no-op or plain CF semantics
      }
      c.handle.cancel(); // exchange and backoff are dead before any callback can observe this
      c.observe(CallObserver.Outcome.CANCELLED, null, null);
      try {
        return super.cancel(
            mayInterruptIfRunning); // callbacks run on the cancelling caller's thread
      } finally {
        c.releaseTracking(); // cancellation is visible on the result: release tracking (idempotent)
      }
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
      return new CompletableFuture<>();
    }
  }

  /** Logs through this client's diagnostic sink (its configured level applies). */
  private void log(Level level, Supplier<String> message) {
    diagnostics.log(level, message);
  }

  private static Optional<String> firstHeader(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (name.equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
        return Optional.of(e.getValue().get(0));
      }
    }
    return Optional.empty();
  }

  private static Duration min(Duration a, Duration b) {
    return a.compareTo(b) <= 0 ? a : b;
  }
}
