package net.codefinch.jev.benchmarks.load;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.codefinch.jev.CallObserver;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.JevClientBuilder;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.RetryPolicy;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.benchmarks.server.LoopbackServer;
import net.codefinch.jev.internal.RequestWriter;
import net.codefinch.jev.micrometer.JevMetrics;
import net.codefinch.jev.model.SystemOneRequest;
import net.codefinch.jev.model.SystemOneResponse;

/** One owned client/server lifetime, sharing prepared input across separate drained cohorts. */
public final class LoadSession implements AutoCloseable {
  private static final Duration DRAIN = Duration.ofSeconds(5);
  private final LoadCase cell;
  private final LoopbackServer server;
  private final SystemOneRequest request;
  private final HttpRequest controlRequest;
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final ThreadPoolExecutor operations;
  private final JevClient client;
  private final HttpClient transport;
  private final AtomicLong ids = new AtomicLong();
  private volatile ObservedCalls observations;

  /** Starts only a managed loopback server, with explicit credentials and configuration. */
  public LoadSession(LoadCase cell) throws IOException {
    this.cell = cell;
    request = Payloads.request(Payloads.content(cell.payload()), Payloads.count(cell.payload()));
    server =
        new LoopbackServer(
            cell.payload(),
            Math.max(16, cell.concurrency() * 2),
            cell.headerDelayMillis(),
            cell.bodyDelayMillis(),
            cell.status());
    operations =
        cell.variant() == LoadCase.Variant.PLATFORM
            ? new ThreadPoolExecutor(
                8,
                8,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(128),
                Thread.ofPlatform().name("benchmark-operation-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy())
            : null;
    HttpRequest.Builder raw =
        HttpRequest.newBuilder(
                server
                    .uri()
                    .resolve(
                        cell.variant() == LoadCase.Variant.MODELS ? "/v1/models" : "/v1/systemone"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer benchmark-dummy")
            .header("Content-Type", "application/json");
    controlRequest =
        cell.variant() == LoadCase.Variant.MODELS
            ? raw.GET().build()
            : raw.POST(
                    HttpRequest.BodyPublishers.ofString(RequestWriter.write(request, "jev-latest")))
                .build();
    transport = cell.control() && cell.variant() != LoadCase.Variant.COLD ? newTransport() : null;
    client =
        !cell.control() && cell.variant() != LoadCase.Variant.COLD ? clientBuilder().build() : null;
  }

  private JevClientBuilder clientBuilder() {
    LoopbackServer.requireManagedEndpoint(server.uri(), server.uri());
    JevClientBuilder builder =
        JevClient.builder()
            .apiKey("benchmark-dummy")
            .baseUrl(server.uri())
            .defaultModel("jev-latest")
            .logLevel(cell.variant() == LoadCase.Variant.LOGGING ? Level.INFO : Level.WARNING)
            .retryPolicy(RetryPolicy.NONE)
            .timeout(Duration.ofSeconds(15))
            .deadline(Duration.ofSeconds(20))
            .closeGracePeriod(Duration.ofSeconds(1))
            .publicationTimeout(Duration.ofSeconds(2));
    if (operations != null) {
      builder.executor(operations);
    }
    if (cell.observed()) {
      builder.observer(
          new CallObserver() {
            @Override
            public void onAttempt(Attempt attempt) {
              observations.onAttempt(attempt);
            }

            @Override
            public void onCall(Call call) {
              observations.onCall(call);
            }
          });
    }
    return builder;
  }

  private static HttpClient newTransport() {
    return HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build();
  }

  /**
   * Measures one cohort; observer work and server handlers drain separately from caller results.
   */
  public Map<String, Object> cohort(Duration duration) throws InterruptedException {
    if (observations != null && !observations.await(DRAIN)) {
      throw new IllegalStateException("Previous observer cohort did not drain");
    }
    observations =
        cell.observed()
            ? new ObservedCalls(
                cell.metrics() ? JevMetrics.of(registry) : new CallObserver() {},
                cell.offsets() ? new ObserverCorrelation(4096) : null)
            : null;
    Map<String, Object> beforeServer = server.snapshot();
    ProcessResources before = ProcessResources.capture();
    Map<String, Object> result =
        new LinkedHashMap<>(LoadDriver.run(cell.concurrency(), duration, DRAIN, this::invoke));
    result.put("resources", ProcessResources.capture().since(before));
    long observerDrainStart = System.nanoTime();
    boolean observed = observations == null || observations.await(DRAIN);
    result.put("observerDrainNanos", System.nanoTime() - observerDrainStart);
    boolean serverIdle = server.awaitIdle(DRAIN);
    Map<String, Object> afterServer = server.snapshot();
    result.put(
        "httpAttempts",
        ((Number) afterServer.get("requests")).longValue()
            - ((Number) beforeServer.get("requests")).longValue());
    // Workloads use RetryPolicy.NONE; retries would need explicit attempt accounting.
    result.put("retries", 0);
    result.put("serverBefore", beforeServer);
    result.put("serverAfter", afterServer);
    result.put(
        "serverMeasurement",
        Map.of(
            "sampledHandlerCpuNanos",
                counterDelta(beforeServer, afterServer, "sampledHandlerCpuNanos"),
            "handlerCpuSamples", counterDelta(beforeServer, afterServer, "handlerCpuSamples"),
            "handlerCpuSamplingInterval", 64,
            "executorQueueNanos", counterDelta(beforeServer, afterServer, "executorQueueNanos"),
            "executorTasks", counterDelta(beforeServer, afterServer, "executorTasks"),
            "peaksScope",
                "peak counts in serverAfter are cumulative from trial start, including warm-up"));
    result.put("serverDrained", serverIdle);
    result.put("observersDrained", observed);
    if (observations != null) {
      result.put("observation", observations.snapshot());
    }
    result.put("meters", registry.getMeters().size());
    result.put(
        "observerInstrumentation",
        cell.observed() ? "callback counters included; correlation only in offsets cells" : "none");
    return result;
  }

  private static long counterDelta(
      Map<String, Object> before, Map<String, Object> after, String key) {
    return ((Number) after.get(key)).longValue() - ((Number) before.get(key)).longValue();
  }

  private LoadAccounting.Outcome invoke() throws InterruptedException {
    String id = cell.offsets() ? Long.toString(ids.incrementAndGet()) : "";
    RequestOptions options =
        cell.offsets()
            ? RequestOptions.builder().header("X-Benchmark-Call", id).build()
            : RequestOptions.NONE;
    if (observations != null) {
      observations.register(id);
    }
    try {
      if (cell.control()) {
        return invokeControl();
      }
      if (cell.variant() == LoadCase.Variant.COLD) {
        try (JevClient fresh = clientBuilder().build()) {
          fresh.systemOne(request, options);
        }
      } else if (cell.variant() == LoadCase.Variant.MODELS) {
        if (cell.submission() == LoadCase.Submission.SYNC) {
          client.models();
        } else {
          await(client.modelsAsync());
        }
      } else {
        SystemOneResponse response =
            cell.submission() == LoadCase.Submission.SYNC
                ? client.systemOne(request, options)
                : await(client.systemOneAsync(request, options));
        long observed = System.nanoTime();
        if (observations != null && cell.offsets()) {
          observations.result(response.requestId().orElseThrow(), observed);
        }
      }
      return LoadAccounting.Outcome.SUCCESS;
    } catch (InterruptedException e) {
      if (observations != null) {
        observations.failed(id);
      }
      throw e;
    } catch (Exception e) {
      if (observations != null) {
        observations.failed(id);
      }
      Throwable failure =
          e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
      return classify(failure);
    }
  }

  private LoadAccounting.Outcome invokeControl()
      throws IOException, InterruptedException, ExecutionException {
    if (cell.variant() == LoadCase.Variant.COLD) {
      try (HttpClient fresh = newTransport()) {
        return status(
            fresh.send(controlRequest, HttpResponse.BodyHandlers.ofString()).statusCode());
      }
    }
    HttpResponse<String> response =
        cell.submission() == LoadCase.Submission.SYNC
            ? transport.send(controlRequest, HttpResponse.BodyHandlers.ofString())
            : await(transport.sendAsync(controlRequest, HttpResponse.BodyHandlers.ofString()));
    return status(response.statusCode());
  }

  private static <T> T await(CompletableFuture<T> future)
      throws InterruptedException, ExecutionException {
    try {
      return future.get();
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    }
  }

  private static LoadAccounting.Outcome status(int status) {
    if (status >= 500) {
      return LoadAccounting.Outcome.HTTP5XX;
    }
    if (status >= 400) {
      return LoadAccounting.Outcome.HTTP4XX;
    }
    return LoadAccounting.Outcome.SUCCESS;
  }

  private static LoadAccounting.Outcome classify(Throwable failure) {
    if (failure instanceof net.codefinch.jev.exception.JevApiException api) {
      return api.status() >= 400 ? status(api.status()) : LoadAccounting.Outcome.OTHER;
    }
    if (failure instanceof net.codefinch.jev.exception.JevDeadlineExceededException) {
      return LoadAccounting.Outcome.DEADLINE;
    }
    if (failure instanceof java.util.concurrent.CancellationException
        || failure instanceof net.codefinch.jev.exception.JevInterruptedException) {
      return LoadAccounting.Outcome.CANCELLED;
    }
    return LoadAccounting.Outcome.OTHER;
  }

  /**
   * SDK-owned resources close first; caller-owned operation threads and registry close explicitly.
   */
  @Override
  public void close() {
    try {
      if (client != null) {
        client.close();
      }
      if (transport != null) {
        transport.close();
      }
    } finally {
      try {
        if (operations != null) {
          operations.shutdownNow();
          try {
            if (!operations.awaitTermination(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Operation executor did not terminate");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        }
      } finally {
        registry.close();
        server.close();
      }
    }
  }
}
