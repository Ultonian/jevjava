package net.codefinch.jev.benchmarks.load;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import net.codefinch.jev.JevClient;
import net.codefinch.jev.RetryPolicy;
import net.codefinch.jev.benchmarks.fixtures.Payloads;
import net.codefinch.jev.internal.RequestWriter;
import net.codefinch.jev.model.SystemOneRequest;

/**
 * SDK lifecycle experiment without a server, socket, observer or replacement SDK implementation.
 */
final class ImmediateSession implements AutoCloseable {
  private final LoadCase cell;
  private final ImmediateHttpClient transport = new ImmediateHttpClient();
  private final SystemOneRequest request = Payloads.request(Payloads.content("ticket"), 3);
  private final int expectedRequestBytes =
      RequestWriter.write(request, "jev-latest").getBytes(StandardCharsets.UTF_8).length;
  private final JevClient client;

  ImmediateSession(LoadCase cell) {
    if (cell.variant() != LoadCase.Variant.IMMEDIATE || cell.control()) {
      throw new IllegalArgumentException("Expected an immediate SDK cell");
    }
    this.cell = cell;
    client =
        JevClient.builder()
            .apiKey("benchmark-dummy")
            .baseUrl(ImmediateHttpClient.ENDPOINT.resolve("/"))
            .httpClient(transport)
            .defaultModel("jev-latest")
            .retryPolicy(RetryPolicy.NONE)
            .timeout(Duration.ofSeconds(15))
            .deadline(Duration.ofSeconds(20))
            .closeGracePeriod(Duration.ofSeconds(1))
            .publicationTimeout(Duration.ofSeconds(2))
            .logLevel(System.Logger.Level.WARNING)
            .build();
  }

  Map<String, Object> cohort(Duration duration) throws InterruptedException {
    final long attempts = transport.attempts();
    final long bytes = transport.requestBytes();
    ProcessResources before = ProcessResources.capture();
    Map<String, Object> result =
        new LinkedHashMap<>(
            LoadDriver.run(cell.concurrency(), duration, Duration.ofSeconds(5), this::invoke));
    Map<String, Object> resources = new LinkedHashMap<>(ProcessResources.capture().since(before));
    resources.put("scope", "SDK + synthetic transport + driver; measurement and result drain");
    result.put("resources", resources);
    result.put("transportKind", "immediate-in-memory");
    result.put("httpAttempts", transport.attempts() - attempts);
    result.put("requestBytes", transport.requestBytes() - bytes);
    result.put("expectedRequestBytesPerAttempt", expectedRequestBytes);
    result.put("retries", 0);
    result.put("observerInstrumentation", "none");
    result.put("observersDrained", true);
    return result;
  }

  private LoadAccounting.Outcome invoke() throws InterruptedException {
    try {
      if (cell.submission() == LoadCase.Submission.SYNC) {
        client.systemOne(request);
      } else {
        var future = client.systemOneAsync(request);
        try {
          future.get();
        } catch (InterruptedException e) {
          future.cancel(true);
          throw e;
        }
      }
      return LoadAccounting.Outcome.SUCCESS;
    } catch (ExecutionException | RuntimeException e) {
      return LoadAccounting.Outcome.OTHER;
    }
  }

  @Override
  public void close() {
    try {
      client.close();
    } finally {
      transport.close();
    }
  }
}
