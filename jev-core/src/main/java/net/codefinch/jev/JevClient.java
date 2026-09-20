package net.codefinch.jev;

import java.util.concurrent.CompletableFuture;

/**
 * A client for the Jev System One API. Implemented by the HTTP client (Phase 2) and by the
 * recording fake in {@code jev-test}, so application code and its tests share one contract.
 *
 * <h2>Threading</h2>
 *
 * <p>Implementations are immutable and thread-safe; one instance per process is the intended use.
 * Synchronous calls run on the caller's thread. Asynchronous calls run on the client's executor.
 *
 * <h2>Timeouts and deadline</h2>
 *
 * <p>The per-attempt timeout (default 10 s, as upstream) covers headers and body delivery of one
 * HTTP attempt. The operation deadline (default 30 s; Java-only) covers everything from submission
 * through every attempt and backoff sleep; at expiry the in-flight attempt is cancelled and the
 * call fails with {@link JevDeadlineExceededException}.
 *
 * <h2>Cancellation and interruption</h2>
 *
 * <p>Cancelling the future returned by an async method aborts the in-flight HTTP exchange and any
 * backoff sleep; no further attempt is started, and the future completes with {@link
 * java.util.concurrent.CancellationException}. Interrupting the thread of a synchronous call does
 * the same and throws {@link JevInterruptedException} with the interrupt flag re-asserted. Both are
 * terminal: retry rules and predicates are never consulted afterwards.
 *
 * <h2>Completion threads</h2>
 *
 * <p>Futures returned by async methods are completed on an SDK-owned virtual thread, after the call
 * has been fully torn down. Callbacks attached with non-{@code Async} methods therefore run on that
 * thread (or on the caller's own thread for {@code cancel()}), never on the deadline timer or the
 * thread calling {@link #close()}; a blocking callback delays only its own future.
 *
 * <h2>Shutdown</h2>
 *
 * <p>{@link #close()} is idempotent. It stops accepting calls, waits up to the close grace period
 * for in-flight calls to finish, cancels whatever remains, and then waits up to the publication
 * timeout for those cancelled results to become done — a state transition on an SDK thread that
 * never depends on application callbacks. It then shuts down only the resources the client created
 * itself; a caller-supplied {@code HttpClient} or {@code Executor} is never shut down, though its
 * in-flight SDK calls are still cancelled. Calls issued after {@code close()} throw {@link
 * IllegalStateException}.
 *
 * <p>When {@code close()} returns normally, every future this client handed out is done. If the
 * publication timeout expires or the closing thread is interrupted, {@code close()} still shuts
 * down owned resources and then throws {@link JevException} (re-asserting the interrupt flag), so
 * an unmet guarantee is observable rather than silent. Both timeouts are configurable on the
 * builder; the total bound is grace period plus publication timeout.
 *
 * <p>This holds for every invocation, not only the first: a concurrent or repeated {@code close()}
 * waits (within the same total bound) for the shutdown started by the first caller, then re-checks
 * that every future is done — waiting up to the publication timeout again if some are not — and
 * throws under the same rules. Resources are shut down exactly once.
 */
public interface JevClient extends AutoCloseable {

  /** Answers the questions synchronously with the client's default model. */
  default SystemOneResponse systemOne(State state, Questions questions) {
    return systemOne(SystemOneRequest.of(state, questions));
  }

  /** Answers the questions synchronously. */
  default SystemOneResponse systemOne(SystemOneRequest request) {
    return systemOne(request, RequestOptions.NONE);
  }

  /** Answers the questions synchronously with per-call HTTP options. */
  SystemOneResponse systemOne(SystemOneRequest request, RequestOptions options);

  /** Answers the questions asynchronously with the client's default model. */
  default CompletableFuture<SystemOneResponse> systemOneAsync(State state, Questions questions) {
    return systemOneAsync(SystemOneRequest.of(state, questions));
  }

  /** Answers the questions asynchronously. */
  default CompletableFuture<SystemOneResponse> systemOneAsync(SystemOneRequest request) {
    return systemOneAsync(request, RequestOptions.NONE);
  }

  /** Answers the questions asynchronously with per-call HTTP options. */
  CompletableFuture<SystemOneResponse> systemOneAsync(
      SystemOneRequest request, RequestOptions options);

  /** Lists the models and aliases this account may send. */
  default ModelList models() {
    return models(RequestOptions.NONE);
  }

  /** Lists the models and aliases with per-call HTTP options. */
  ModelList models(RequestOptions options);

  /** Lists the models and aliases asynchronously. */
  default CompletableFuture<ModelList> modelsAsync() {
    return modelsAsync(RequestOptions.NONE);
  }

  /** Lists the models and aliases asynchronously with per-call HTTP options. */
  CompletableFuture<ModelList> modelsAsync(RequestOptions options);

  /** Shuts the client down; see the class documentation. Never throws a checked exception. */
  @Override
  void close();

  /** Starts configuring an HTTP client. */
  static JevClientBuilder builder() {
    return new JevClientBuilder();
  }

  /** An HTTP client configured entirely from the environment ({@code TYPESAFE_*} variables). */
  static JevClient fromEnv() {
    return builder().build();
  }
}
