package net.codefinch.jev;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A scripted HTTP server on the JDK's {@code HttpServer}: enqueue responses, then inspect the
 * recorded requests. Responses can stall before headers or after a partial body.
 */
public final class TestServer implements AutoCloseable {

  /** One recorded request. */
  public record Recorded(
      String method, String path, Map<String, List<String>> headers, String body) {
    /** First value of a header, case-insensitively, or null. */
    public String header(String name) {
      List<String> values = headers.get(name);
      return values == null || values.isEmpty() ? null : values.get(0);
    }
  }

  /** One scripted response. */
  public record Scripted(
      int status,
      Map<String, String> headers,
      String body,
      Duration stallBeforeHeaders,
      int partialBodyBytes,
      CountDownLatch release) {

    public static Scripted json(int status, String body) {
      return new Scripted(
          status, Map.of("Content-Type", "application/json"), body, Duration.ZERO, -1, null);
    }

    public static Scripted of(int status, Map<String, String> headers, String body) {
      return new Scripted(status, headers, body, Duration.ZERO, -1, null);
    }

    /** Holds the response (no headers) for the given time before answering normally. */
    public Scripted stallingHeaders(Duration stall) {
      return new Scripted(status, headers, body, stall, partialBodyBytes, release);
    }

    /**
     * Sends the headers and only the first {@code bytes} of the body, then blocks until released.
     */
    public Scripted stallingBody(int bytes, CountDownLatch release) {
      return new Scripted(status, headers, body, stallBeforeHeaders, bytes, release);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Queue<Scripted> responses = new ConcurrentLinkedQueue<>();
  private final List<Recorded> requests = new CopyOnWriteArrayList<>();
  private final List<CountDownLatch> pendingReleases = new CopyOnWriteArrayList<>();

  public TestServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  public URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  public TestServer enqueue(Scripted response) {
    responses.add(response);
    return this;
  }

  public TestServer enqueueJson(int status, String body) {
    return enqueue(Scripted.json(status, body));
  }

  public List<Recorded> requests() {
    return List.copyOf(requests);
  }

  public Recorded lastRequest() {
    return requests.get(requests.size() - 1);
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
    Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, new ArrayList<>(v)));
    requests.add(
        new Recorded(
            exchange.getRequestMethod(),
            exchange.getRequestURI().toString(),
            headers,
            new String(bodyBytes, StandardCharsets.UTF_8)));
    Scripted scripted = responses.poll();
    if (scripted == null) {
      scripted = Scripted.json(500, "{\"message\":\"no scripted response\"}");
    }
    if (!scripted.stallBeforeHeaders().isZero()) {
      try {
        Thread.sleep(scripted.stallBeforeHeaders().toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exchange.close();
        return;
      }
    }
    scripted.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
    byte[] body = scripted.body().getBytes(StandardCharsets.UTF_8);
    if (scripted.partialBodyBytes() >= 0) {
      pendingReleases.add(scripted.release());
      exchange.sendResponseHeaders(scripted.status(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body, 0, scripted.partialBodyBytes());
        out.flush();
        scripted.release().await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // client went away
      }
      return;
    }
    exchange.sendResponseHeaders(scripted.status(), body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    } else {
      exchange.close();
    }
  }

  @Override
  public void close() {
    pendingReleases.forEach(CountDownLatch::countDown);
    server.stop(0);
    executor.shutdownNow();
  }
}
