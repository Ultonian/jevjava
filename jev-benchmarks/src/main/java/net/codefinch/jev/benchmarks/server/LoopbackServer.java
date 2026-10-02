package net.codefinch.jev.benchmarks.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.codefinch.jev.benchmarks.fixtures.Payloads;

/** Bounded, non-recording HTTP fixture. All bytes are synthetic and prepared before serving. */
public final class LoopbackServer implements AutoCloseable {
  private static final int BODY_CAP = 1024 * 1024;
  private final HttpServer server;
  private final ThreadPoolExecutor executor;
  private final byte[] response;
  private final byte[] models =
      ("{\"models\":[{\"name\":\"jev-latest\",\"description\":\"Synthetic model\","
              + "\"release_date\":\"2026-09-27\"}]}")
          .getBytes(StandardCharsets.UTF_8);
  private final byte[] error =
      "{\"error\":\"synthetic benchmark error\"}".getBytes(StandardCharsets.UTF_8);
  private final int headerDelayMillis;
  private final int bodyDelayMillis;
  private final int status;
  private final AtomicLong requests = new AtomicLong();
  private final AtomicLong failures = new AtomicLong();
  private final java.util.concurrent.atomic.AtomicLongArray statuses =
      new java.util.concurrent.atomic.AtomicLongArray(600);
  private final AtomicLong serverCpuNanos = new AtomicLong();
  private final AtomicLong cpuSamples = new AtomicLong();
  private final AtomicLong queuedNanos = new AtomicLong();
  private final AtomicLong tasks = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger peakActive = new AtomicInteger();
  private final AtomicInteger peakQueue = new AtomicInteger();
  private final Map<String, PatternState> patterns = new HashMap<>();
  private volatile String protocol = "not observed";

  /** Creates a managed IPv4 loopback endpoint; no caller-supplied address is accepted. */
  public LoopbackServer(
      String payload, int workers, int headerDelayMillis, int bodyDelayMillis, int status)
      throws IOException {
    if (workers < 1
        || workers > 256
        || headerDelayMillis < 0
        || headerDelayMillis > 1000
        || bodyDelayMillis < 0
        || bodyDelayMillis > 1000
        || !java.util.Set.of(200, 400, 429, 503).contains(status)) {
      throw new IllegalArgumentException("Invalid bounded server configuration");
    }
    response = Payloads.response(payload).getBytes(StandardCharsets.UTF_8);
    this.headerDelayMillis = headerDelayMillis;
    this.bodyDelayMillis = bodyDelayMillis;
    this.status = status;
    executor =
        new ThreadPoolExecutor(
            workers,
            workers,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().name("benchmark-server-", 0).factory(),
            (task, pool) -> {
              rejected.incrementAndGet();
              throw new java.util.concurrent.RejectedExecutionException("Server queue full");
            });
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
    server.setExecutor(
        task -> {
          long queued = System.nanoTime();
          executor.execute(
              () -> {
                queuedNanos.addAndGet(System.nanoTime() - queued);
                tasks.incrementAndGet();
                task.run();
              });
          peakQueue.accumulateAndGet(executor.getQueue().size(), Math::max);
        });
    server.createContext("/", this::handle);
    server.start();
  }

  /** The only endpoint clients in this harness may use. */
  public URI uri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** Rejects endpoint overrides before constructing an HTTP client or sending any request. */
  public static void requireManagedEndpoint(URI candidate, URI managed) {
    if (!candidate.equals(managed)
        || !"http".equals(candidate.getScheme())
        || !"127.0.0.1".equals(candidate.getHost())
        || candidate.getPort() <= 0
        || candidate.getRawUserInfo() != null
        || candidate.getRawQuery() != null
        || candidate.getRawFragment() != null
        || !candidate.getPath().isEmpty()) {
      throw new IllegalArgumentException("Only the harness-managed loopback endpoint is allowed");
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    boolean sampleCpu = requests.incrementAndGet() % 64 == 0;
    long cpuStart = sampleCpu ? cpuTime() : 0;
    peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
    try (exchange) {
      protocol = exchange.getProtocol();
      int total = 0;
      byte[] buffer = new byte[8192];
      int read;
      while ((read = exchange.getRequestBody().read(buffer)) != -1) {
        total += read;
        if (total > BODY_CAP) {
          exchange.sendResponseHeaders(413, -1);
          statuses.incrementAndGet(413);
          return;
        }
      }
      String path = exchange.getRequestURI().getPath();
      int result = status;
      if (!(path.equals("/v1/systemone") && exchange.getRequestMethod().equals("POST"))
          && !(path.equals("/v1/models") && exchange.getRequestMethod().equals("GET"))) {
        result = 404;
      }
      String id = exchange.getRequestHeaders().getFirst("X-Benchmark-Call");
      if (id != null && id.matches("[0-9]{1,20}")) {
        exchange.getResponseHeaders().set("x-typesafe-request-id", id);
        String pattern = exchange.getRequestHeaders().getFirst("X-Benchmark-Pattern");
        if (pattern != null) {
          result = scriptedStatus(id, pattern);
        }
      }
      byte[] bytes = result == 200 ? (path.equals("/v1/models") ? models : response) : error;
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      pause(headerDelayMillis);
      exchange.sendResponseHeaders(result, bytes.length);
      statuses.incrementAndGet(result);
      pause(bodyDelayMillis);
      exchange.getResponseBody().write(bytes);
    } catch (IOException e) {
      failures.incrementAndGet();
      throw e;
    } finally {
      if (sampleCpu) {
        serverCpuNanos.addAndGet(Math.max(0, cpuTime() - cpuStart));
        cpuSamples.incrementAndGet();
      }
      active.decrementAndGet();
    }
  }

  private synchronized int scriptedStatus(String id, String pattern) {
    if (!pattern.matches("(?:200|400|429|503)(?:,(?:200|400|429|503)){0,4}")) {
      return 400;
    }
    long now = System.nanoTime();
    patterns.values().removeIf(state -> now - state.started() > TimeUnit.SECONDS.toNanos(30));
    PatternState state = patterns.get(id);
    if (state == null) {
      if (patterns.size() >= 512) {
        return 503;
      }
      state = new PatternState(pattern, 0, now);
    }
    if (!state.pattern().equals(pattern)) {
      return 400;
    }
    String[] statuses = pattern.split(",");
    int result = Integer.parseInt(statuses[state.next()]);
    if (state.next() + 1 == statuses.length) {
      patterns.remove(id);
    } else {
      patterns.put(id, new PatternState(pattern, state.next() + 1, state.started()));
    }
    return result;
  }

  private record PatternState(String pattern, int next, long started) {}

  private static void pause(int millis) throws IOException {
    if (millis == 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Server interrupted", e);
    }
  }

  private static long cpuTime() {
    var bean = ManagementFactory.getThreadMXBean();
    return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
        ? bean.getCurrentThreadCpuTime()
        : 0;
  }

  /** Cumulative bounded telemetry, including executor task queueing (not socket accept latency). */
  public Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("requests", requests.get());
    result.put("activeHandlers", active.get());
    result.put("peakActiveHandlers", peakActive.get());
    result.put("workers", executor.getCorePoolSize());
    result.put("queueCapacity", 256);
    result.put("peakExecutorQueue", peakQueue.get());
    result.put("executorTasks", tasks.get());
    result.put("executorQueueNanos", queuedNanos.get());
    result.put("sampledHandlerCpuNanos", serverCpuNanos.get());
    result.put("handlerCpuSamples", cpuSamples.get());
    result.put("handlerCpuSamplingInterval", 64);
    result.put(
        "handlerCpuInterpretation", "sum for every 64th request; not extrapolated total CPU");
    result.put("handlerCpuSupported", ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled());
    result.put("rejectedTasks", rejected.get());
    result.put("ioFailures", failures.get());
    Map<String, Long> statusCounts = new java.util.TreeMap<>();
    for (int code : java.util.List.of(200, 400, 404, 413, 429, 503)) {
      statusCounts.put(Integer.toString(code), statuses.get(code));
    }
    result.put("headersSentByStatus", statusCounts);
    result.put("protocol", protocol);
    result.put("providerClass", server.getClass().getName());
    result.put("tcpNoDelayRequested", Boolean.getBoolean("sun.net.httpserver.nodelay"));
    result.put("connectionLimitRequested", Integer.getInteger("jdk.httpserver.maxConnections", -1));
    result.put("bodyCapBytes", BODY_CAP);
    result.put(
        "responseFixtures",
        Map.of(
            "systemOneBytes",
            response.length,
            "systemOneSha256",
            Payloads.hash(response),
            "modelsBytes",
            models.length,
            "modelsSha256",
            Payloads.hash(models),
            "errorBytes",
            error.length,
            "errorSha256",
            Payloads.hash(error)));
    result.put("connections", "not observable without retaining connection identities");
    return result;
  }

  /** Waits for handler completion separately from client result completion. */
  public boolean awaitIdle(Duration limit) throws InterruptedException {
    long end = System.nanoTime() + limit.toNanos();
    while (active.get() != 0 && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    return active.get() == 0;
  }

  /** Stops accept/handler work and releases all owned platform threads. */
  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Server workers did not terminate");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted during server cleanup", e);
    }
  }
}
