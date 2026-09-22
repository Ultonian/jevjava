package net.codefinch.jev.internal;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.codefinch.jev.JevConnectionException;
import net.codefinch.jev.JevException;
import net.codefinch.jev.JevInterruptedException;
import net.codefinch.jev.JevTimeoutException;
import net.codefinch.jev.RequestOptions;

/** Executes one HTTP exchange; operation policy and publication belong to the call. */
final class HttpExchange {
  /** Headers the SDK sets itself; caller values for these are ignored. */
  private static final Set<String> PROTECTED =
      Set.of(
          "authorization",
          "accept",
          "content-type",
          "user-agent",
          "x-typesafe-sdk",
          "x-typesafe-runtime",
          "x-typesafe-retry-count");

  private final ClientConfig config;
  private final Diagnostics diagnostics;

  HttpExchange(ClientConfig config, Diagnostics diagnostics) {
    this.config = config;
    this.diagnostics = diagnostics;
  }

  HttpResponse<String> execute(
      CallSpec<?> spec, RequestOptions options, CallHandle handle, int attempt, Duration budget) {
    return exchange(spec, handle, buildRequest(spec, options, attempt, budget), budget);
  }

  private HttpResponse<String> exchange(
      CallSpec<?> spec, CallHandle handle, HttpRequest request, Duration budget) {
    CompletableFuture<HttpResponse<String>> exchange;
    try {
      exchange =
          config
              .httpClient()
              .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw new JevException("invalid request: " + e.getMessage(), e);
    }
    handle.inFlight(exchange);
    HttpResponse<String> response;
    try {
      response = exchange.get(budget.toNanos(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      exchange.cancel(true);
      diagnostics.log(
          Level.INFO, () -> spec.endpoint() + " timed out after " + budget.toMillis() + "ms");
      throw new JevTimeoutException(
          spec.endpoint() + ": attempt timed out after " + budget.toMillis() + " ms", e);
    } catch (InterruptedException e) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw new JevInterruptedException(spec.endpoint() + ": interrupted", e);
    } catch (CancellationException e) {
      diagnostics.log(Level.INFO, () -> spec.endpoint() + " aborted by caller");
      throw cancelled(spec, e);
    } catch (ExecutionException e) {
      JevException failure = mapTransportFailure(spec, e.getCause());
      diagnostics.log(
          Level.INFO, () -> spec.endpoint() + " <- " + failure.getClass().getSimpleName());
      throw failure;
    } finally {
      handle.clearInFlight();
    }
    if (handle.isCancelled()) {
      throw cancelled(spec, null);
    }
    return response;
  }

  private HttpRequest buildRequest(
      CallSpec<?> spec, RequestOptions options, int attempt, Duration budget) {
    final HttpRequest.Builder builder = HttpRequest.newBuilder(spec.uri()).timeout(budget);
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.putAll(config.defaultHeaders());
    headers.putAll(options.headers());
    headers.keySet().removeIf(name -> PROTECTED.contains(name.toLowerCase(Locale.ROOT)));
    headers.put("Authorization", "Bearer " + config.apiKey());
    headers.put("Accept", "application/json");
    headers.put("User-Agent", Version.SDK);
    headers.put("X-TypeSafe-SDK", Version.SDK);
    headers.put("X-TypeSafe-Runtime", Version.RUNTIME);
    if (attempt > 0) {
      headers.put(HttpJevClient.RETRY_COUNT_HEADER, Integer.toString(attempt));
    }
    if (spec.body().isPresent()) {
      headers.put("Content-Type", "application/json");
      builder.method(spec.method(), HttpRequest.BodyPublishers.ofString(spec.body().get()));
    } else {
      builder.method(spec.method(), HttpRequest.BodyPublishers.noBody());
    }
    try {
      headers.forEach(builder::header);
    } catch (IllegalArgumentException e) {
      throw new JevException("invalid request header: " + e.getMessage(), e);
    }
    diagnostics.log(
        Level.DEBUG,
        () ->
            "-> "
                + spec.endpoint()
                + " headers "
                + Redaction.headers(headers)
                + " body "
                + spec.body().orElse(""));
    return builder.build();
  }

  private JevException mapTransportFailure(CallSpec<?> spec, Throwable cause) {
    if (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
      return new JevTimeoutException(spec.endpoint() + ": " + cause.getMessage(), cause);
    }
    if (cause instanceof IOException) {
      return new JevConnectionException(spec.endpoint() + ": " + cause.getMessage(), cause);
    }
    if (cause instanceof JevException jev) {
      return jev;
    }
    return new JevConnectionException(spec.endpoint() + ": " + cause, cause);
  }

  private CancellationException cancelled(CallSpec<?> spec, Throwable cause) {
    CancellationException e = new CancellationException(spec.endpoint() + ": call cancelled");
    if (cause != null) {
      e.initCause(cause);
    }
    return e;
  }
}
