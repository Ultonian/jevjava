package net.codefinch.jev.internal;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.JevApiException;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevDeadlineExceededException;
import net.codefinch.jev.JevException;
import net.codefinch.jev.JevInterruptedException;
import net.codefinch.jev.JevRateLimitException;
import net.codefinch.jev.ModelList;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.RetryPolicy;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;

/**
 * The HTTP {@link JevClient}. See the interface for the lifecycle contract; this class is not API.
 *
 * <p>Every call is a {@link Call}: it captures its submission time before any executor hand-off, so
 * the operation deadline covers queueing. Async calls additionally arm a timer on the client's own
 * scheduler that, at the absolute deadline, fails the public future with {@link
 * JevDeadlineExceededException} and cancels the call — even if the caller's executor never ran it.
 * {@link #close()} completes every outstanding future with {@link CancellationException} for the
 * same reason.
 *
 * <p>Lifecycle bookkeeping is separated from delivery of the public result: every terminal path
 * first wins {@link Call#finish()} (which disarms the timer and, for abnormal ends, cancels the
 * handle) and only then hands the completion to the delivery executor — by default a fresh virtual
 * thread per completion, so there is no pool to shut down and no rejection path. Application
 * callbacks therefore never run on the deadline scheduler, on the thread calling {@code close()},
 * or before the call's HTTP exchange and backoff are cancelled. A call stays tracked until its
 * result is <em>published</em> (the future is done), so {@code close()} can wait for publication —
 * a state transition that cannot block on application code — without waiting for callbacks.
 * Observers are application code too: their events are built on the control path but dispatched on
 * delivery threads, serialised per call, so a blocked observer delays nothing but its own queue.
 * Admission of a call is atomic with the transition to closed, under {@code lifecycle}. Only the
 * first {@code close()} shuts resources down; later or concurrent callers wait for its outcome and
 * then re-check publication, so every {@code close()} that returns normally has verified that every
 * future is done.
 */
public final class HttpJevClient implements JevClient {
  private static final Logger LOG = System.getLogger(HttpJevClient.class.getName());

  /** Path of the System One endpoint. */
  public static final String SYSTEM_ONE_PATH = "/v1/systemone";

  /** Path of the models endpoint. */
  public static final String MODELS_PATH = "/v1/models";

  /** Header carrying the zero-based retry number on retries; stripped from caller headers. */
  public static final String RETRY_COUNT_HEADER = "X-TypeSafe-Retry-Count";

  private final ClientConfig config;
  private final Diagnostics diagnostics;
  private final HttpExchange exchange;

  private final Set<Call<?>> inFlight = ConcurrentHashMap.newKeySet();
  private final ScheduledThreadPoolExecutor scheduler;
  private final Object lifecycle = new Object();
  private Phase phase = Phase.OPEN; // guarded by lifecycle
  private final CountDownLatch shutdownDone = new CountDownLatch(1);

  private enum Phase {
    OPEN,
    CLOSING,
    CLOSED
  }

  /** Creates the client; use {@code JevClient.builder()}. */
  public HttpJevClient(ClientConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.diagnostics = Diagnostics.of(LOG, config.logLevel());
    this.exchange = new HttpExchange(config, diagnostics);
    this.scheduler =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "jev-deadline");
              t.setDaemon(true);
              return t;
            });
    scheduler.setRemoveOnCancelPolicy(true);
  }

  /** The resolved configuration (credentials redacted in its string form). */
  public ClientConfig config() {
    return config;
  }

  /** Number of calls currently tracked (admitted and not yet published). For diagnostics. */
  public int trackedCalls() {
    return inFlight.size();
  }

  /** True once {@link #close()} has been called (admission closed), even if shutdown is ongoing. */
  public boolean isClosed() {
    synchronized (lifecycle) {
      return phase != Phase.OPEN;
    }
  }

  /** True once the first closer's shutdown has completed, whether or not it succeeded. */
  public boolean isShutdownComplete() {
    synchronized (lifecycle) {
      return phase == Phase.CLOSED;
    }
  }

  @Override
  public SystemOneResponse systemOne(SystemOneRequest request, RequestOptions options) {
    Objects.requireNonNull(request, "request");
    return runSync(systemOneSpec(request, options), options);
  }

  @Override
  public CompletableFuture<SystemOneResponse> systemOneAsync(
      SystemOneRequest request, RequestOptions options) {
    Objects.requireNonNull(request, "request");
    return runAsync(systemOneSpec(request, options), options);
  }

  @Override
  public ModelList models(RequestOptions options) {
    return runSync(modelsSpec(options), options);
  }

  @Override
  public CompletableFuture<ModelList> modelsAsync(RequestOptions options) {
    return runAsync(modelsSpec(options), options);
  }

  @Override
  public void close() {
    boolean first;
    synchronized (lifecycle) {
      first = phase == Phase.OPEN;
      if (first) {
        phase = Phase.CLOSING; // no call can be admitted after this point
      }
    }
    if (first) {
      try {
        shutdown();
      } finally {
        synchronized (lifecycle) {
          phase = Phase.CLOSED;
        }
        shutdownDone.countDown();
      }
    } else {
      observeShutdown();
    }
  }

  /**
   * A later or concurrent {@code close()}: wait for the first closer's outcome (bounded by the same
   * grace + publication budget from this caller's start), then re-check publication so a normal
   * return is truthful even after an earlier publication failure. Never shuts resources down again.
   */
  private void observeShutdown() {
    long budget = config.closeGracePeriod().toNanos() + config.publicationTimeout().toNanos();
    long overallDeadline = config.nanoTime().getAsLong() + budget; // one budget for both waits
    try {
      if (!shutdownDone.await(budget, TimeUnit.NANOSECONDS)) {
        throw new JevException(
            "close(): shutdown started by another caller is still in progress after "
                + Duration.ofNanos(budget).toMillis()
                + " ms");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JevException("close(): interrupted while waiting for shutdown to complete", e);
    }
    if (unpublishedCount() == 0) {
      return; // the guarantee holds: every future is done
    }
    long publicationDeadline =
        Math.min(
            overallDeadline, config.nanoTime().getAsLong() + config.publicationTimeout().toNanos());
    Publication outcome = awaitPublication(publicationDeadline);
    if (outcome == Publication.INTERRUPTED) {
      Thread.currentThread().interrupt();
    }
    if (outcome != Publication.DONE) {
      throw unpublished(outcome.name().toLowerCase(Locale.ROOT));
    }
  }

  /** The first closer's work. */
  private void shutdown() {
    long deadline = config.nanoTime().getAsLong() + config.closeGracePeriod().toNanos();
    boolean interrupted = false;
    while (anyUnfinished() && config.nanoTime().getAsLong() < deadline) {
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        interrupted = true;
        break;
      }
    }
    for (Call<?> call : inFlight) {
      call.cancelFromClient();
    }
    String unpublished = null;
    if (!interrupted) {
      Publication outcome =
          awaitPublication(config.nanoTime().getAsLong() + config.publicationTimeout().toNanos());
      interrupted = outcome == Publication.INTERRUPTED;
      if (outcome != Publication.DONE) {
        unpublished = outcome.name().toLowerCase(Locale.ROOT);
      }
    } else {
      unpublished = "interrupted";
    }
    scheduler.shutdownNow();
    if (config.ownsHttpClient()) {
      shutdownHttp(deadline);
    }
    if (config.ownsExecutor()) {
      shutdownExecutor(deadline);
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (unpublished != null) {
      throw unpublished(unpublished);
    }
  }

  private JevException unpublished(String reason) {
    return new JevException(
        "close(): "
            + unpublishedCount()
            + " result(s) still unpublished ("
            + reason
            + " after grace "
            + config.closeGracePeriod().toMillis()
            + " ms + publication timeout "
            + config.publicationTimeout().toMillis()
            + " ms); owned resources were shut down");
  }

  private int unpublishedCount() {
    int n = 0;
    for (Call<?> call : inFlight) {
      if (call.future != null && !call.future.isDone()) {
        n++;
      }
    }
    return n;
  }

  private void shutdownHttp(long deadlineNanos) {
    HttpClient http = config.httpClient();
    http.shutdown();
    try {
      if (!http.awaitTermination(remaining(deadlineNanos))) {
        http.shutdownNow();
      }
    } catch (InterruptedException e) {
      http.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void shutdownExecutor(long deadlineNanos) {
    config.executor().shutdown();
    try {
      if (!config
          .executor()
          .awaitTermination(remaining(deadlineNanos).toNanos(), TimeUnit.NANOSECONDS)) {
        config.executor().shutdownNow();
      }
    } catch (InterruptedException e) {
      config.executor().shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private boolean anyUnfinished() {
    for (Call<?> call : inFlight) {
      if (!call.isFinished()) {
        return true;
      }
    }
    return false;
  }

  private enum Publication {
    DONE,
    TIMED_OUT,
    INTERRUPTED
  }

  /**
   * Waits until every outstanding async result is done — the completion CAS on its future, which
   * happens on a delivery thread before any callback runs and so cannot block on application code.
   * Bounded by an absolute monotonic deadline the caller derives from its own budget; expiry or
   * interruption makes {@link #close()} throw after shutting down owned resources.
   */
  private Publication awaitPublication(long until) {
    while (true) {
      if (unpublishedCount() == 0) {
        return Publication.DONE;
      }
      if (config.nanoTime().getAsLong() >= until) {
        return Publication.TIMED_OUT;
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        return Publication.INTERRUPTED;
      }
    }
  }

  private Duration remaining(long deadlineNanos) {
    return Duration.ofNanos(Math.max(0, deadlineNanos - config.nanoTime().getAsLong()));
  }

  // ---------------------------------------------------------------------------------------------
  // Call specs
  // ---------------------------------------------------------------------------------------------

  private CallSpec<SystemOneResponse> systemOneSpec(
      SystemOneRequest request, RequestOptions options) {
    Objects.requireNonNull(options, "options");
    URI uri = URI.create(config.baseUrl() + SYSTEM_ONE_PATH);
    String body = RequestWriter.write(request, config.defaultModel());
    return new CallSpec<>(
        "systemone",
        "POST",
        uri,
        "POST " + uri,
        Optional.of(body),
        (st, h, b, e) -> ResponseParser.parseSystemOne(st, h, b, e, diagnostics));
  }

  private CallSpec<ModelList> modelsSpec(RequestOptions options) {
    Objects.requireNonNull(options, "options");
    URI uri = URI.create(config.baseUrl() + MODELS_PATH);
    return new CallSpec<>(
        "models", "GET", uri, "GET " + uri, Optional.empty(), ResponseParser::parseModels);
  }

  // ---------------------------------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------------------------------

  private <T> T runSync(CallSpec<T> spec, RequestOptions options) {
    Call<T> call = new Call<>(spec, options, null); // may run caller code; outside the lock
    admit(call);
    T result;
    try {
      result = call.run();
    } catch (Throwable t) {
      if (call.finish()) {
        call.observe(outcomeOf(t), null, t);
      }
      inFlight.remove(call); // sync: the throw is the publication
      throw t;
    }
    if (call.finish()) {
      call.observe(CallObserver.Outcome.SUCCESS, result, null);
    }
    inFlight.remove(call); // sync: the return is the publication
    return result;
  }

  private <T> CompletableFuture<T> runAsync(CallSpec<T> spec, RequestOptions options) {
    CallFuture<T> future = new CallFuture<>();
    Call<T> call = new Call<>(spec, options, future);
    future.call = call;
    admit(call);
    call.armDeadline();
    try {
      config
          .executor()
          .execute(
              () -> {
                if (call.handle.isCancelled() || call.isFinished()) {
                  return; // cancelled, expired or closed while queued
                }
                T result;
                try {
                  result = call.run();
                } catch (Throwable t) {
                  if (call.finish()) {
                    call.observe(outcomeOf(t), null, t);
                    call.deliver(() -> future.completeExceptionally(t));
                  }
                  return;
                }
                if (call.finish()) {
                  call.observe(CallObserver.Outcome.SUCCESS, result, null);
                  call.deliver(() -> future.complete(result));
                }
              });
    } catch (RejectedExecutionException e) {
      if (call.finish()) {
        call.handle.cancel();
        JevException failure = new JevException("executor rejected the call", e);
        call.observe(CallObserver.Outcome.ERROR, null, failure);
        call.deliver(() -> future.completeExceptionally(failure));
      }
    }
    return future;
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

  /** Registers a call atomically with the closed check, so shutdown always sees it. */
  private void admit(Call<?> call) {
    synchronized (lifecycle) {
      if (phase != Phase.OPEN) {
        throw new IllegalStateException("JevClient is closed");
      }
      inFlight.add(call);
    }
  }

  /** A {@link CompletableFuture} whose cancellation reaches the running call first. */
  private static final class CallFuture<T> extends CompletableFuture<T> {
    private volatile Call<T> call;

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      Call<T> c = call;
      if (c == null || !c.finish()) {
        return super.cancel(mayInterruptIfRunning); // already terminal: no-op or plain CF semantics
      }
      c.handle.cancel(); // exchange and backoff are dead before any callback can observe this
      c.observe(CallObserver.Outcome.CANCELLED, null, null);
      try {
        return super.cancel(
            mayInterruptIfRunning); // callbacks run on the cancelling caller's thread
      } finally {
        c.published(); // cancellation is visible on the result: release tracking (idempotent)
      }
    }

    @Override
    public <U> CompletableFuture<U> newIncompleteFuture() {
      return new CompletableFuture<>();
    }
  }

  /** One operation: attempts, retries, deadline, cancellation. */
  private final class Call<T> {
    final CallHandle handle = new CallHandle();
    private final CallSpec<T> spec;
    private final RequestOptions options;
    private final RetryPolicy retry;
    private final Duration attemptTimeout;
    private final Optional<Duration> deadline;
    private final long submittedAt;
    private final Optional<Long> deadlineAt;
    private final CallFuture<T> future; // null for synchronous calls
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile ScheduledFuture<?> timer;
    private volatile int attempts;
    private volatile JevException last;
    private volatile int lastStatus = -1;
    private CompletableFuture<Void> observerChain = CompletableFuture.completedFuture(null);

    Call(CallSpec<T> spec, RequestOptions options, CallFuture<T> future) {
      this.spec = spec;
      this.options = options;
      this.future = future;
      this.retry = options.resolveRetry(config.retry());
      this.attemptTimeout = options.timeout().orElse(config.timeout());
      this.deadline =
          options.deadlineDisabled() ? Optional.empty() : options.deadline().or(config::deadline);
      this.submittedAt = config.nanoTime().getAsLong();
      this.deadlineAt = deadline.map(d -> submittedAt + d.toNanos());
    }

    /** Schedules expiry of an async call independently of its executor. */
    void armDeadline() {
      deadlineAt.ifPresent(
          at -> {
            long delay = Math.max(0, at - config.nanoTime().getAsLong());
            try {
              timer = scheduler.schedule(this::expire, delay, TimeUnit.NANOSECONDS);
              if (finished.get()) {
                timer.cancel(false); // finished before the timer was stored
              }
            } catch (RejectedExecutionException e) {
              cancelFromClient(); // scheduler is down: the client closed under us
            }
          });
    }

    /**
     * Claims the terminal state. Exactly one caller wins and must then deliver the outcome. Winning
     * disarms the timer and never touches application callbacks. The call stays tracked until the
     * outcome is published, so {@link #close()} can still see it in the hand-off gap.
     */
    boolean finish() {
      synchronized (
          this) { // atomic with startAttempt(): termination and attempt start never interleave
        if (!finished.compareAndSet(false, true)) {
          return false;
        }
      }
      ScheduledFuture<?> t = timer;
      if (t != null) {
        t.cancel(false);
      }
      return true;
    }

    boolean isFinished() {
      return finished.get();
    }

    /** The outcome is visible on the public result; stop tracking. Idempotent. */
    void published() {
      inFlight.remove(this);
    }

    /**
     * Publishes a completion on a delivery thread, never on the caller of this method. The default
     * delivery starts a fresh virtual thread and cannot reject; an injected delivery that does is
     * backed up by one, so no completion ever runs inline on a control thread.
     */
    void deliver(Runnable completion) {
      safeDelivery(
          () -> {
            try {
              completion.run();
            } finally {
              published();
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
     * Reserves the next position in this call's event sequence. An attempt reserves its slot when
     * it starts and fills it when it ends, so a terminal event enqueued while the attempt is still
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
      if (finish()) {
        handle.cancel();
        JevDeadlineExceededException failure = deadlineExceeded(attempts, last);
        observe(CallObserver.Outcome.DEADLINE, null, failure);
        deliver(() -> future.completeExceptionally(failure));
      }
    }

    /** {@link #close()} reached this call before it finished. */
    void cancelFromClient() {
      if (finish()) {
        handle.cancel();
        observe(CallObserver.Outcome.CANCELLED, null, null);
        if (future != null) {
          CancellationException cancelled =
              new CancellationException(spec.endpoint() + ": client closed");
          deliver(() -> future.completeExceptionally(cancelled));
        }
      }
    }

    /** Emits the call event to every observer; exactly one per call, from the finish() winner. */
    void observe(CallObserver.Outcome outcome, Object result, Throwable failure) {
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
        CompletableFuture<Runnable> slot,
        int attempt,
        long started,
        int status,
        Throwable failure) {
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

    T run() {
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
     * Decides, atomically with any terminal transition, that this attempt starts: if the call has
     * already finished the attempt never starts; otherwise its observer slot is reserved before any
     * terminal event can be enqueued, so attempts always precede the call event.
     */
    private CompletableFuture<Runnable> startAttempt(int attempt) {
      synchronized (this) {
        if (finished.get() || handle.isCancelled()) {
          throw cancelled(null);
        }
        attempts = attempt + 1;
        return config.observers().isEmpty() ? null : reserveObserverSlot();
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
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

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
