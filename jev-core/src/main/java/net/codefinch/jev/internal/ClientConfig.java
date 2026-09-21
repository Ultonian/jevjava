package net.codefinch.jev.internal;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.RetryPolicy;

/**
 * Fully resolved client settings plus the seams the transport uses for time, randomness and
 * sleeping. Built by {@code JevClient.builder()}; not API.
 *
 * @param apiKey the bearer token
 * @param baseUrl base URL with trailing slashes removed
 * @param defaultModel model sent when a request has no override
 * @param timeout per-attempt timeout
 * @param deadline operation deadline, or empty when disabled
 * @param retry retry policy
 * @param defaultHeaders client-level extra headers
 * @param closeGracePeriod how long {@code close()} waits for in-flight calls
 * @param publicationTimeout how long {@code close()} then waits for cancelled results to be
 *     published
 * @param logLevel minimum level this client logs at
 * @param httpClient the transport
 * @param ownsHttpClient whether {@code close()} shuts the transport down
 * @param executor where async calls run
 * @param ownsExecutor whether {@code close()} shuts the executor down
 * @param nanoTime monotonic clock
 * @param random jitter source
 * @param sleeper backoff waiter
 * @param delivery where public completions are published (default: a fresh virtual thread each)
 * @param observers receive attempt and call events; never affect the call
 */
public record ClientConfig(
    String apiKey,
    URI baseUrl,
    String defaultModel,
    Duration timeout,
    Optional<Duration> deadline,
    RetryPolicy retry,
    Map<String, String> defaultHeaders,
    Duration closeGracePeriod,
    Duration publicationTimeout,
    Level logLevel,
    HttpClient httpClient,
    boolean ownsHttpClient,
    ExecutorService executor,
    boolean ownsExecutor,
    LongSupplier nanoTime,
    RandomGenerator random,
    Sleeper sleeper,
    Executor delivery,
    List<CallObserver> observers) {

  /** Snapshots the headers into an unmodifiable, case-insensitive map. */
  public ClientConfig {
    Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    copy.putAll(defaultHeaders);
    defaultHeaders = Collections.unmodifiableMap(copy);
    observers = List.copyOf(observers);
  }

  /** Omits the API key and redacts credential-bearing default headers. */
  @Override
  public String toString() {
    return "ClientConfig[apiKey="
        + Redaction.REDACTED
        + ", baseUrl="
        + baseUrl
        + ", defaultModel="
        + defaultModel
        + ", timeout="
        + timeout
        + ", deadline="
        + deadline.map(Duration::toString).orElse("disabled")
        + ", retry="
        + retry
        + ", defaultHeaders="
        + Redaction.headers(defaultHeaders)
        + ", closeGracePeriod="
        + closeGracePeriod
        + ", publicationTimeout="
        + publicationTimeout
        + ", logLevel="
        + logLevel
        + ", ownsHttpClient="
        + ownsHttpClient
        + ", ownsExecutor="
        + ownsExecutor
        + "]";
  }
}
