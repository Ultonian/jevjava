package net.codefinch.jev.internal;

import java.lang.System.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevException;
import net.codefinch.jev.ModelList;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.SystemOneRequest;
import net.codefinch.jev.SystemOneResponse;

/**
 * The HTTP {@link JevClient}. Owns admission, outstanding-call tracking and bounded resource
 * shutdown. Each admitted {@link CallExecution} owns its operation state and publication.
 *
 * <p>Admission and the transition to closing share one lock. Cancellation and application code run
 * outside it. Close checks public-future state, never callback completion or registry emptiness;
 * concurrent and repeated closers recheck publication within their own bounded shutdown budget. See
 * {@code docs/INTERNAL_ARCHITECTURE.md} for the threading and ownership map.
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

  private final Set<CallExecution<?>> inFlight = ConcurrentHashMap.newKeySet();
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

  /** Number of calls retained for tracking, including pending delivery callbacks. */
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
    for (CallExecution<?> call : inFlight) {
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
    for (CallExecution<?> call : inFlight) {
      if (call.hasUnpublishedResult()) {
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
    for (CallExecution<?> call : inFlight) {
      if (!call.hasClaimedOutcome()) {
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
    CallExecution<T> call = createCall(spec, options, false);
    admit(call);
    return call.runSync();
  }

  private <T> CompletableFuture<T> runAsync(CallSpec<T> spec, RequestOptions options) {
    CallExecution<T> call = createCall(spec, options, true);
    admit(call);
    return call.runAsync();
  }

  /** Resolves caller-provided options outside the admission lock. */
  private <T> CallExecution<T> createCall(
      CallSpec<T> spec, RequestOptions options, boolean asynchronous) {
    return new CallExecution<>(
        spec, options, asynchronous, config, diagnostics, exchange, scheduler, inFlight::remove);
  }

  /** Registers a call atomically with the closed check, so shutdown always sees it. */
  private void admit(CallExecution<?> call) {
    synchronized (lifecycle) {
      if (phase != Phase.OPEN) {
        throw new IllegalStateException("JevClient is closed");
      }
      inFlight.add(call);
    }
  }
}
