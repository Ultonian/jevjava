package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import net.codefinch.jev.internal.HttpJevClient;
import net.codefinch.jev.internal.Sleeper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HttpJevClientTest {
  private static final String OK = Fixtures.read("responses/docs-all-three.json");
  private static final String MODELS = Fixtures.read("responses/models.json");
  private static final Questions QUESTIONS = Questions.of("q", NoulQuestion.of("?"));
  private static final SystemOneRequest REQUEST = SystemOneRequest.of(State.of("s"), QUESTIONS);

  private TestServer server;

  /** Delays the client asked to sleep, without sleeping. */
  private final List<Duration> sleeps = new ArrayList<>();

  private final Sleeper fakeSleeper =
      (delay, handle) -> {
        sleeps.add(delay);
        return !handle.isCancelled();
      };

  @BeforeEach
  void start() throws IOException {
    server = new TestServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private JevClientBuilder client() {
    return JevClient.builder()
        .apiKey("test-key")
        .baseUrl(server.baseUrl())
        .sleeper(fakeSleeper)
        .random(
            new RandomGenerator() {
              @Override
              public long nextLong() {
                return 0; // nextDouble() -> 0.0: no jitter
              }
            });
  }

  // ---------------------------------------------------------------------------------------------
  // Happy paths and headers
  // ---------------------------------------------------------------------------------------------

  @Test
  void systemOneSendsTheDocumentedRequestAndParsesTheResponse() {
    server.enqueue(
        TestServer.Scripted.of(
            200, Map.of("Content-Type", "application/json", "x-typesafe-request-id", "req-1"), OK));
    try (JevClient c = client().defaultHeader("X-Client", "d").build()) {
      SystemOneResponse r =
          c.systemOne(REQUEST, RequestOptions.builder().header("x-call", "c").build());
      assertThat(r.model()).isEqualTo("jev-1.13.0");
      assertThat(r.requestId()).contains("req-1");
      assertThat(r.answers().noul("refund_requested").noul()).isEqualTo(0.93);
    }
    TestServer.Recorded req = server.lastRequest();
    assertThat(req.method()).isEqualTo("POST");
    assertThat(req.path()).isEqualTo("/v1/systemone");
    assertThat(req.header("Authorization")).isEqualTo("Bearer test-key");
    assertThat(req.header("Accept")).isEqualTo("application/json");
    assertThat(req.header("Content-Type")).isEqualTo("application/json");
    assertThat(req.header("User-Agent")).startsWith("jev-java/");
    assertThat(req.header("X-TypeSafe-SDK")).isEqualTo(req.header("User-Agent"));
    assertThat(req.header("X-TypeSafe-Runtime")).startsWith("java/").contains("(");
    assertThat(req.header("X-TypeSafe-Retry-Count")).isNull();
    assertThat(req.header("X-Client")).isEqualTo("d");
    assertThat(req.header("x-call")).isEqualTo("c");
    assertThat(req.body())
        .isEqualTo(
            "{\"model\":\"jev-latest\",\"state\":\"s\",\"questions\":{\"q\":{\"type\":\"noul\",\"instructions\":\"?\"}}}");
  }

  @Test
  void modelsSendsGetWithoutBodyOrContentType() {
    server.enqueueJson(200, MODELS);
    try (JevClient c = client().build()) {
      ModelList list = c.models();
      assertThat(list.models())
          .extracting(ModelMetadata::name)
          .containsExactly("jev-latest", "jev-preview");
    }
    TestServer.Recorded req = server.lastRequest();
    assertThat(req.method()).isEqualTo("GET");
    assertThat(req.path()).isEqualTo("/v1/models");
    assertThat(req.header("Content-Type")).isNull();
    assertThat(req.body()).isEmpty();
  }

  @Test
  void asyncVariantsWork() throws Exception {
    server.enqueueJson(200, OK).enqueueJson(200, MODELS);
    try (JevClient c = client().build()) {
      assertThat(c.systemOneAsync(State.of("s"), QUESTIONS).get(5, TimeUnit.SECONDS).model())
          .isEqualTo("jev-1.13.0");
      assertThat(c.modelsAsync().get(5, TimeUnit.SECONDS).models()).hasSize(2);
    }
  }

  @Test
  void sdkHeadersAlwaysWinAndMergeIsCaseInsensitive() {
    server.enqueueJson(200, OK);
    try (JevClient c =
        client()
            .defaultHeader("authorization", "Bearer wrong")
            .defaultHeader("X-Shared", "client")
            .defaultHeader("x-typesafe-retry-count", "9")
            .build()) {
      c.systemOne(
          REQUEST,
          RequestOptions.builder()
              .header("CONTENT-TYPE", "text/plain")
              .header("x-shared", "request")
              .header("User-Agent", "spoof")
              .build());
    }
    TestServer.Recorded req = server.lastRequest();
    assertThat(req.header("Authorization")).isEqualTo("Bearer test-key");
    assertThat(req.header("Content-Type")).isEqualTo("application/json");
    assertThat(req.header("User-Agent")).startsWith("jev-java/");
    assertThat(req.header("X-Shared")).isEqualTo("request");
    assertThat(req.header("X-TypeSafe-Retry-Count")).isNull();
  }

  @Test
  void perCallModelOverridesAndConcurrentCallsUseTheirOwnModel() throws Exception {
    server.enqueueJson(200, OK).enqueueJson(200, OK);
    try (JevClient c = client().defaultModel("jev-default").build()) {
      CompletableFuture<SystemOneResponse> a = c.systemOneAsync(REQUEST.withModel("jev-preview"));
      CompletableFuture<SystemOneResponse> b = c.systemOneAsync(REQUEST);
      a.get(5, TimeUnit.SECONDS);
      b.get(5, TimeUnit.SECONDS);
    }
    assertThat(server.requests())
        .extracting(r -> r.body().contains("\"jev-preview\""))
        .containsExactlyInAnyOrder(true, false);
    assertThat(server.requests())
        .extracting(r -> r.body().contains("\"jev-default\""))
        .containsExactlyInAnyOrder(false, true);
  }

  @Test
  void baseUrlPrefixAndTrailingSlashesAreHandled() {
    server.enqueueJson(200, MODELS);
    try (JevClient c = client().baseUrl(URI.create(server.baseUrl() + "/prefix//")).build()) {
      c.models();
    }
    assertThat(server.lastRequest().path()).isEqualTo("/prefix/v1/models");
  }

  // ---------------------------------------------------------------------------------------------
  // Error mapping
  // ---------------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "400, JevBadRequestException",
    "401, JevAuthenticationException",
    "403, JevPermissionDeniedException",
    "404, JevNotFoundException",
    "422, JevUnprocessableEntityException",
    "418, JevApiException",
  })
  void nonRetriedStatusesMapWithoutRetrying(int status, String cls) {
    server.enqueue(
        TestServer.Scripted.of(
            status, Map.of("x-typesafe-request-id", "req-e"), "{\"message\":\"nope\"}"));
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevApiException.class)
          .satisfies(e -> assertThat(e.getClass().getSimpleName()).isEqualTo(cls))
          .hasMessage(
              "POST " + server.baseUrl() + "/v1/systemone: " + status + " nope (request_id=req-e)");
    }
    assertThat(server.requests()).hasSize(1);
    assertThat(sleeps).isEmpty();
  }

  @Test
  void missingKeyStyle403IsPermissionDeniedWithTheServerMessage() {
    server.enqueueJson(
        403,
        "{\"detail\":{\"error_type\":\"authentication_error\",\"message\":\"Must supply an API"
            + " key!\"}}");
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.models())
          .isInstanceOf(JevPermissionDeniedException.class)
          .hasMessageContaining("403 Must supply an API key!");
    }
  }

  @Test
  void malformedSuccessBodyIsValidationErrorWithEndpoint() {
    server.enqueueJson(
        200,
        "{\"model\":\"m\",\"answers\":{\"q\":{\"type\":\"noul\"}},\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevResponseValidationException.class)
          .hasMessage(
              "POST "
                  + server.baseUrl()
                  + "/v1/systemone: 200 Invalid response data at 'answers.q.noul'.");
    }
    assertThat(server.requests()).hasSize(1); // not retried by default
  }

  @Test
  void unknownAnswerTypeIsDroppedEndToEnd() {
    server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
    try (JevClient c = client().build()) {
      SystemOneResponse r = c.systemOne(REQUEST);
      assertThat(r.answers().ids()).containsExactly("spam");
      assertThat(r.rawBody()).contains("aurora");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Retries
  // ---------------------------------------------------------------------------------------------

  @Test
  void retriesThenSucceedsWithRetryCountHeaderAndBackoff() {
    server.enqueueJson(429, "{}").enqueueJson(503, "{}").enqueueJson(200, OK);
    try (JevClient c = client().build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    List<TestServer.Recorded> reqs = server.requests();
    assertThat(reqs).hasSize(3);
    assertThat(reqs.get(0).header("X-TypeSafe-Retry-Count")).isNull();
    assertThat(reqs.get(1).header("X-TypeSafe-Retry-Count")).isEqualTo("1");
    assertThat(reqs.get(2).header("X-TypeSafe-Retry-Count")).isEqualTo("2");
    assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
  }

  @Test
  void retriesExhaustedThrowsTheLastFailure() {
    server
        .enqueueJson(500, "{\"message\":\"one\"}")
        .enqueueJson(502, "{\"message\":\"two\"}")
        .enqueueJson(529, "{\"message\":\"three\"}");
    try (JevClient c = client().build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevInternalServerException.class)
          .hasMessageContaining("529 three")
          .satisfies(e -> assertThat(((JevInternalServerException) e).isOverloaded()).isTrue());
    }
    assertThat(server.requests()).hasSize(3);
  }

  @Test
  void retryAfterIsHonouredPreferringMsAndIgnoredAboveTheCap() {
    server
        .enqueue(
            TestServer.Scripted.of(429, Map.of("retry-after-ms", "1234", "retry-after", "9"), ""))
        .enqueue(TestServer.Scripted.of(429, Map.of("retry-after", "2"), ""))
        .enqueue(TestServer.Scripted.of(503, Map.of("retry-after", "61"), ""))
        .enqueueJson(200, OK);
    try (JevClient c = client().retryPolicy(RetryPolicy.DEFAULT.withMaxRetries(3)).build()) {
      c.systemOne(REQUEST);
    }
    assertThat(sleeps)
        .containsExactly(Duration.ofMillis(1234), Duration.ofSeconds(2), Duration.ofMillis(2000));
  }

  @Test
  void perCallRetryOverrideIsPartial() {
    server.enqueueJson(500, "{}").enqueueJson(200, OK);
    try (JevClient c = client().build()) {
      assertThatThrownBy(
              () ->
                  c.systemOne(
                      REQUEST, RequestOptions.builder().retry(p -> p.withMaxRetries(0)).build()))
          .isInstanceOf(JevInternalServerException.class);
      assertThat(server.requests()).hasSize(1);
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
  }

  @Test
  void connectionFailureIsRetriedThenMapped() {
    server.close(); // nothing listening any more
    AtomicInteger attempts = new AtomicInteger();
    try (JevClient c =
        client()
            .retryPolicy(
                RetryPolicy.DEFAULT.withPredicate(
                    e -> {
                      attempts.incrementAndGet();
                      return false;
                    }))
            .build()) {
      assertThatThrownBy(() -> c.models())
          .isInstanceOf(JevConnectionException.class)
          .isNotInstanceOf(JevTimeoutException.class);
    }
    assertThat(sleeps).hasSize(2);
    assertThat(attempts.get())
        .isZero(); // built-in rule decided; predicate only consulted when it did not
  }

  @Test
  void predicateCanOptIntoRetryingValidationFailures() {
    server.enqueueJson(200, "not json").enqueueJson(200, OK);
    try (JevClient c =
        client()
            .retryPolicy(
                RetryPolicy.DEFAULT.withPredicate(e -> e instanceof JevResponseValidationException))
            .build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    assertThat(server.requests()).hasSize(2);
  }

  // ---------------------------------------------------------------------------------------------
  // Timeouts and deadline
  // ---------------------------------------------------------------------------------------------

  @Test
  void stalledHeadersTimeOutPerAttemptAndAreRetried() {
    server
        .enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)))
        .enqueueJson(200, OK);
    try (JevClient c = client().timeout(Duration.ofMillis(200)).build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    assertThat(server.requests()).hasSize(2);
  }

  @Test
  void stalledBodyTimesOutToo() {
    CountDownLatch release = new CountDownLatch(1);
    server.enqueue(TestServer.Scripted.json(200, OK).stallingBody(20, release));
    try (JevClient c =
        client().timeout(Duration.ofMillis(300)).retryPolicy(RetryPolicy.NONE).build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevTimeoutException.class)
          .isNotInstanceOf(JevDeadlineExceededException.class)
          .hasMessageContaining("timed out after 300 ms");
    } finally {
      release.countDown();
    }
  }

  @Test
  void deadlineRefusesRetryThatWouldBreachIt() {
    AtomicLong nanos = new AtomicLong();
    server.enqueueJson(500, "{\"message\":\"first\"}");
    try (JevClient c = client().nanoTime(nanos::get).deadline(Duration.ofMillis(400)).build()) {
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevDeadlineExceededException.class)
          .hasMessageContaining("400 ms exceeded after 1 attempt(s)")
          .hasCauseInstanceOf(JevInternalServerException.class);
    }
    assertThat(server.requests()).hasSize(1);
    assertThat(sleeps).isEmpty(); // 500 ms backoff >= 400 ms deadline: fail fast, no sleep
  }

  @Test
  void deadlineExpiringDuringAnActiveAttemptCancelsIt() {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (JevClient c =
        client().timeout(Duration.ofSeconds(10)).deadline(Duration.ofMillis(300)).build()) {
      long start = System.nanoTime();
      assertThatThrownBy(() -> c.systemOne(REQUEST))
          .isInstanceOf(JevDeadlineExceededException.class)
          .hasCauseInstanceOf(JevTimeoutException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void deadlineCanBeDisabledPerCallAndPerClient() {
    server.enqueueJson(500, "{}").enqueueJson(200, OK).enqueueJson(500, "{}").enqueueJson(200, OK);
    AtomicLong nanos = new AtomicLong();
    try (JevClient c = client().nanoTime(nanos::get).deadline(Duration.ofMillis(100)).build()) {
      assertThat(c.systemOne(REQUEST, RequestOptions.builder().noDeadline().build()).model())
          .isEqualTo("jev-1.13.0");
    }
    try (JevClient c = client().nanoTime(nanos::get).noDeadline().build()) {
      assertThat(c.systemOne(REQUEST).model()).isEqualTo("jev-1.13.0");
    }
    assertThat(server.requests()).hasSize(4);
  }

  // ---------------------------------------------------------------------------------------------
  // Cancellation, interruption, shutdown
  // ---------------------------------------------------------------------------------------------

  @Test
  void cancelBeforeStartNeverSendsRequest() throws Exception {
    ExecutorService gate = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    gate.submit(() -> block.await(10, TimeUnit.SECONDS)); // occupies the only thread
    try (JevClient c = client().executor(gate).build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      assertThat(f.cancel(true)).isTrue();
      block.countDown();
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
      Thread.sleep(100);
      assertThat(server.requests()).isEmpty();
    } finally {
      gate.shutdownNow();
    }
    assertThat(gate.isShutdown()).isTrue();
  }

  @Test
  void cancelDuringAnAttemptAbortsIt() throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (JevClient c = client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      awaitRequests(1);
      long start = System.nanoTime();
      f.cancel(true);
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void cancelDuringBackoffStartsNoFurtherAttemptEvenWithPermissivePredicate() throws Exception {
    server.enqueueJson(500, "{}").enqueueJson(200, OK);
    CountDownLatch sleeping = new CountDownLatch(1);
    Sleeper blocking =
        (delay, handle) -> {
          sleeping.countDown();
          return !handle.awaitCancel(Duration.ofSeconds(10));
        };
    try (JevClient c =
        client()
            .sleeper(blocking)
            .retryPolicy(RetryPolicy.DEFAULT.withPredicate(e -> true))
            .build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      assertThat(sleeping.await(5, TimeUnit.SECONDS)).isTrue();
      f.cancel(true);
      assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS))
          .isInstanceOf(CancellationException.class);
    }
    Thread.sleep(100);
    assertThat(server.requests()).hasSize(1);
  }

  @Test
  void cancelRacingTheNextRetryIsRespectedOnBothExecutors() throws Exception {
    for (ExecutorService callerOwned :
        new ExecutorService[] {null, Executors.newFixedThreadPool(2)}) {
      server.enqueueJson(
          500, "{}"); // only one scripted response per iteration: a 2nd request must not happen
      AtomicReference<CompletableFuture<SystemOneResponse>> holder = new AtomicReference<>();
      // Cancel from inside the sleep: the worker returns "completed" but must still stop.
      Sleeper cancelFromInside =
          (delay, handle) -> {
            while (holder.get() == null) {
              Thread.onSpinWait(); // the caller may not have stored the future yet
            }
            holder.get().cancel(true);
            return true;
          };
      JevClientBuilder b = client().sleeper(cancelFromInside);
      if (callerOwned != null) {
        b.executor(callerOwned);
      }
      int before = server.requests().size();
      try (JevClient c = b.build()) {
        holder.set(c.systemOneAsync(REQUEST));
        assertThatThrownBy(() -> holder.get().get(2, TimeUnit.SECONDS))
            .isInstanceOf(CancellationException.class);
      }
      Thread.sleep(100);
      assertThat(server.requests().size() - before).isEqualTo(1);
      if (callerOwned != null) {
        assertThat(callerOwned.isShutdown()).as("caller-owned executor untouched").isFalse();
        callerOwned.shutdownNow();
      }
    }
  }

  @Test
  void interruptingSyncCallThrowsAndReassertsTheFlag() throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (JevClient c = client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      Throwable[] thrown = new Throwable[1];
      boolean[] flag = new boolean[1];
      Thread t =
          new Thread(
              () -> {
                try {
                  c.systemOne(REQUEST);
                } catch (Throwable e) {
                  thrown[0] = e;
                  flag[0] = Thread.currentThread().isInterrupted();
                }
              });
      t.start();
      awaitRequests(1);
      t.interrupt();
      t.join(5000);
      assertThat(thrown[0]).isInstanceOf(JevInterruptedException.class);
      assertThat(flag[0]).as("interrupt flag re-asserted").isTrue();
    }
  }

  @Test
  void closeWaitsThenCancelsAndRejectsNewCalls() throws Exception {
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    ExecutorService callerOwned = Executors.newCachedThreadPool();
    HttpClient callerHttp = HttpClient.newHttpClient();
    JevClient c =
        client()
            .executor(callerOwned)
            .httpClient(callerHttp)
            .timeout(Duration.ofSeconds(10))
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(300))
            .build();
    CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
    awaitRequests(1);
    long start = System.nanoTime();
    c.close();
    c.close(); // idempotent
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThatThrownBy(() -> f.get(2, TimeUnit.SECONDS)).isInstanceOf(CancellationException.class);
    assertThatThrownBy(() -> c.systemOne(REQUEST))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
    assertThatThrownBy(() -> c.modelsAsync()).isInstanceOf(IllegalStateException.class);
    assertThat(callerOwned.isShutdown()).as("caller-owned executor never shut down").isFalse();
    assertThat(callerHttp.isTerminated()).as("caller-owned HttpClient never shut down").isFalse();
    callerOwned.shutdownNow();
  }

  @Test
  void closeWithSdkOwnedResourcesTerminatesThem() {
    server.enqueueJson(200, OK);
    HttpJevClient c = (HttpJevClient) client().build();
    c.systemOne(REQUEST);
    c.close();
    assertThat(c.config().executor().isShutdown()).isTrue();
    assertThat(c.config().httpClient().isTerminated()).isTrue();
  }

  @Test
  void rejectedExecutorFailsTheFutureCleanly() {
    ExecutorService dead = Executors.newSingleThreadExecutor();
    dead.shutdown();
    try (JevClient c = client().executor(dead).build()) {
      assertThatThrownBy(() -> c.modelsAsync().get(1, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(JevException.class);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2 review: queued calls, logging, redirects, diagnostics
  // ---------------------------------------------------------------------------------------------

  /** Review P1: the deadline must cover executor queueing and expire independently of it. */
  @Test
  void queuedCallExpiresAtTheDeadlineWhileTheExecutorIsStillBlocked() throws Exception {
    server.enqueueJson(200, MODELS).enqueueJson(200, MODELS);
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    single.submit(() -> block.await(10, TimeUnit.SECONDS)); // occupies the only worker
    try (JevClient c = client().executor(single).deadline(Duration.ofMillis(150)).build()) {
      long start = System.nanoTime();
      CompletableFuture<ModelList> perClient = c.modelsAsync();
      CompletableFuture<ModelList> perCall =
          c.modelsAsync(
              RequestOptions.builder()
                  .deadline(Duration.ofMillis(200))
                  .retry(p -> RetryPolicy.NONE)
                  .build());
      assertThatThrownBy(() -> perClient.get(2, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(JevDeadlineExceededException.class)
          .cause()
          .hasMessageContaining("150 ms exceeded after 0 attempt(s)");
      assertThatThrownBy(() -> perCall.get(2, TimeUnit.SECONDS))
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
      assertThat(block.getCount())
          .as("executor still blocked when the futures expired")
          .isEqualTo(1);
      block.countDown(); // release: the expired tasks must not send anything
      Thread.sleep(200);
      assertThat(server.requests()).isEmpty();
      assertThat(single.isShutdown()).isFalse();
    } finally {
      block.countDown();
      single.shutdownNow();
    }
  }

  /** Review P1: close() must complete queued futures even if their worker never runs. */
  @Test
  void closeCompletesQueuedFuturesBeforeTheirWorkerEverRuns() throws Exception {
    server.enqueueJson(200, MODELS);
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch block = new CountDownLatch(1);
    single.submit(() -> block.await(10, TimeUnit.SECONDS));
    HttpClient callerHttp =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    JevClient c =
        client()
            .executor(single)
            .httpClient(callerHttp) // caller-owned: no SDK shutdown work can mask ordering gaps
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(30))
            .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    c.close();
    assertThat(queued.isDone()).as("completed by close(), worker still blocked").isTrue();
    assertThat(queued.isCancelled()).isTrue();
    assertThatThrownBy(() -> queued.get(1, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
    assertThat(queued.handle((r, t) -> t).join())
        .isInstanceOf(CancellationException.class)
        .hasMessageContaining("client closed");
    assertThat(single.isShutdown()).as("caller-owned executor untouched").isFalse();
    List<Runnable> discarded = single.shutdownNow(); // caller discards the queued task
    assertThat(discarded).hasSize(1);
    block.countDown();
    Thread.sleep(100);
    assertThat(server.requests()).isEmpty();
  }

  /** Review P2: every diagnostic honours the client's level; secrets never appear. */
  @Test
  void loggingHonoursTheClientLevelAndRedactsCredentials() {
    java.util.logging.Logger jul =
        java.util.logging.Logger.getLogger(HttpJevClient.class.getName());
    List<java.util.logging.LogRecord> records = new ArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Level previous = jul.getLevel();
    jul.setLevel(java.util.logging.Level.ALL);
    jul.addHandler(handler);
    try {
      for (System.Logger.Level level :
          List.of(
              System.Logger.Level.OFF,
              System.Logger.Level.WARNING,
              System.Logger.Level.INFO,
              System.Logger.Level.DEBUG)) {
        records.clear();
        server
            .enqueue(
                TestServer.Scripted.of(
                    500, Map.of("Set-Cookie", "session=abc", "X-Auth-Token", "tok"), "{}"))
            .enqueue(
                TestServer.Scripted.of(
                    200,
                    Map.of("Content-Type", "application/json", "x-typesafe-request-id", "req-log"),
                    OK));
        try (JevClient c =
            client()
                .logLevel(level)
                .defaultHeader("X-Api-Key", "default-secret")
                .defaultHeader("MY-SECRET-thing", "s3")
                .build()) {
          c.systemOne(REQUEST, RequestOptions.builder().header("x-refresh-TOKEN", "t0k").build());
        }
        String all = records.stream().map(r -> r.getMessage()).reduce("", (a, b) -> a + "\n" + b);
        switch (level) {
          case OFF -> assertThat(records).as("OFF logs nothing, not even the retry").isEmpty();
          case WARNING ->
              assertThat(records).as("WARNING (default): nothing for a healthy call").isEmpty();
          case INFO -> {
            assertThat(all).contains("retrying in", "-> 500 in", "-> 200 in", "request_id=req-log");
            assertThat(all).doesNotContain("headers ", "Bearer", "test-key");
          }
          case DEBUG -> {
            assertThat(all).contains("-> POST", "<- headers", "body {", "\"jev-1.13.0\"");
            assertThat(all)
                .contains(
                    "Authorization=[redacted]",
                    "X-Api-Key=[redacted]",
                    "MY-SECRET-thing=[redacted]",
                    "x-refresh-TOKEN=[redacted]")
                .contains("set-cookie=[redacted]", "x-auth-token=[redacted]"); // JDK lower-cases
            assertThat(all)
                .doesNotContain("test-key", "default-secret", "s3", "t0k", "session=abc", "tok\"");
          }
          default -> throw new AssertionError(level);
        }
      }
    } finally {
      jul.removeHandler(handler);
      jul.setLevel(previous);
    }
  }

  /**
   * Review P2: the "never follows redirects" guarantee holds for both transport ownership modes.
   */
  @Test
  void redirectsAreNeverFollowedAndInjectedTransportsMustAgree() throws Exception {
    assertThatThrownBy(
            () ->
                client()
                    .httpClient(
                        HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .build()))
        .isInstanceOf(JevException.class)
        .hasMessageContaining("Redirect.NEVER");
    assertThatThrownBy(
            () ->
                client()
                    .httpClient(
                        HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build()))
        .isInstanceOf(JevException.class);
    try (TestServer other = new TestServer()) {
      String location = other.baseUrl() + "/v1/models";
      for (HttpClient injected :
          new HttpClient[] {
            null, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
          }) {
        server.enqueue(TestServer.Scripted.of(307, Map.of("Location", location), ""));
        JevClientBuilder b = client();
        if (injected != null) {
          b.httpClient(injected);
        }
        try (JevClient c = b.build()) {
          assertThatThrownBy(c::models)
              .isInstanceOf(JevApiException.class)
              .satisfies(e -> assertThat(((JevApiException) e).status()).isEqualTo(307));
        }
        assertThat(other.requests()).as("redirect target never contacted").isEmpty();
      }
    }
  }

  /** Review P3: diagnostics never render the key or credential-bearing default headers. */
  @Test
  void configToStringRedactsCredentials() {
    try (HttpJevClient c =
        (HttpJevClient)
            client()
                .defaultHeader("X-Api-Key", "hdr-secret")
                .defaultHeader("X-Trace", "visible")
                .defaultModel("jev-1.13.0")
                .build()) {
      String s = c.config().toString();
      assertThat(s).doesNotContain("test-key", "hdr-secret");
      assertThat(s)
          .contains(
              "apiKey=[redacted]",
              "X-Api-Key=[redacted]",
              "X-Trace=visible",
              "jev-1.13.0",
              "deadline=PT30S");
    }
    try (HttpJevClient c = (HttpJevClient) client().noDeadline().build()) {
      assertThat(c.config().toString()).contains("deadline=disabled");
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2 fix review: callbacks, admission race, DEBUG bodies
  // ---------------------------------------------------------------------------------------------

  /** Fix-review P1(1): a blocking application callback must not stall other calls' deadlines. */
  @Test
  void blockingCallbackDoesNotBlockOtherDeadlines() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    try (JevClient c = client().executor(single).deadline(Duration.ofMillis(150)).build()) {
      CompletableFuture<ModelList> first = c.modelsAsync();
      first.whenComplete(
          (r, t) -> {
            callbackStarted.countDown();
            try {
              callbackRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<ModelList> second = c.modelsAsync();
      assertThatThrownBy(() -> second.get(2, TimeUnit.SECONDS))
          .as("second deadline fires while the first callback is still blocked")
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      assertThat(callbackRelease.getCount()).isEqualTo(1);
    } finally {
      callbackRelease.countDown();
      occupied.countDown();
      single.shutdownNow();
    }
    assertThat(server.requests()).isEmpty();
  }

  /** Fix-review P1(2): close() stays bounded even when a completion callback blocks. */
  @Test
  void closeIsBoundedDespiteBlockingCallback() throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    JevClient c =
        client().executor(single).noDeadline().closeGracePeriod(Duration.ofMillis(20)).build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    queued.whenComplete(
        (r, t) -> {
          callbackStarted.countDown();
          try {
            callbackRelease.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    Thread closer = new Thread(c::close);
    closer.start();
    try {
      assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
      closer.join(1000);
      assertThat(closer.isAlive())
          .as("close() returned while the callback is still blocked")
          .isFalse();
      assertThat(queued.isCancelled()).isTrue();
    } finally {
      callbackRelease.countDown();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  /**
   * Fix-review P1(3): cancellation kills the exchange before callbacks, so no retry can slip out.
   */
  @Test
  void cancelPreventsRetryWhileTheCancellationCallbackIsBlocked() throws Exception {
    server
        .enqueue(TestServer.Scripted.json(500, "{}").stallingHeaders(Duration.ofMillis(300)))
        .enqueueJson(200, OK);
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    try (JevClient c = client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
      f.whenComplete(
          (r, t) -> {
            callbackStarted.countDown();
            try {
              callbackRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      awaitRequests(1);
      Thread canceller = new Thread(() -> f.cancel(true));
      canceller.start();
      assertThat(callbackStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(f.isCancelled()).isTrue();
      Thread.sleep(700); // the delayed 500 has arrived by now; a retry would have been sent
      assertThat(server.requests()).as("no retry while the callback is blocked").hasSize(1);
      callbackRelease.countDown();
      canceller.join(2000);
    } finally {
      callbackRelease.countDown();
    }
  }

  /**
   * Fix-review P2: a call racing close() is either admitted (and cancelled) or rejected — never
   * lost.
   */
  @ParameterizedTest(name = "async={0} deadline={1}")
  @CsvSource({"true, true", "true, false", "false, true", "false, false"})
  void callRacingCloseNeverEscapesShutdown(boolean async, boolean withDeadline) throws Exception {
    ExecutorService callerExecutor = Executors.newCachedThreadPool();
    HttpClient callerHttp =
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    server.enqueueJson(200, MODELS);
    CountDownLatch inOverride = new CountDownLatch(1);
    CountDownLatch releaseOverride = new CountDownLatch(1);
    RequestOptions pausing =
        RequestOptions.builder()
            .retry(
                p -> {
                  inOverride.countDown(); // requireOpen would have passed here in the old code
                  try {
                    releaseOverride.await(10, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return p;
                })
            .build();
    JevClientBuilder b = client().executor(callerExecutor).httpClient(callerHttp);
    JevClient c = (withDeadline ? b : b.noDeadline()).build();
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    Thread caller =
        new Thread(
            () -> {
              try {
                if (async) {
                  c.modelsAsync(pausing).get(5, TimeUnit.SECONDS);
                } else {
                  c.models(pausing);
                }
              } catch (Throwable t) {
                outcome.set(t);
              }
            });
    caller.start();
    assertThat(inOverride.await(2, TimeUnit.SECONDS)).isTrue();
    c.close();
    releaseOverride.countDown();
    caller.join(5000);
    assertThat(outcome.get()).as("the racing call must fail, not succeed").isNotNull();
    Throwable t = outcome.get();
    assertThat(
            t instanceof IllegalStateException
                || t instanceof CancellationException
                || (t instanceof ExecutionException
                    && t.getCause() instanceof CancellationException))
        .as("rejected at admission or cancelled by close, got " + t)
        .isTrue();
    Thread.sleep(200);
    assertThat(server.requests()).as("no request after close() returned").isEmpty();
    assertThat(callerExecutor.isShutdown()).isFalse();
    callerExecutor.shutdownNow();
  }

  /** Fix-review P2: DEBUG retry lines never carry server body text; TRACE may. */
  @Test
  void debugRetryLogsCarryNoBodyText() {
    java.util.logging.Logger jul =
        java.util.logging.Logger.getLogger(HttpJevClient.class.getName());
    List<java.util.logging.LogRecord> records = new ArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Level previous = jul.getLevel();
    jul.setLevel(java.util.logging.Level.ALL);
    jul.addHandler(handler);
    try {
      for (System.Logger.Level level :
          List.of(
              System.Logger.Level.OFF,
              System.Logger.Level.WARNING,
              System.Logger.Level.INFO,
              System.Logger.Level.DEBUG)) {
        records.clear();
        server
            .enqueue(
                TestServer.Scripted.of(
                    500, Map.of("Content-Type", "text/plain"), "review-raw-body-marker"))
            .enqueue(
                TestServer.Scripted.of(
                    502,
                    Map.of("Content-Type", "application/json"),
                    "{\"code\":\"json-marker-7\"}"))
            .enqueue(
                TestServer.Scripted.of(
                    503,
                    Map.of("Content-Type", "application/json"),
                    "{\"message\":\"known-msg-marker\"}"))
            .enqueueJson(200, OK);
        try (JevClient c =
            client().logLevel(level).retryPolicy(RetryPolicy.DEFAULT.withMaxRetries(3)).build()) {
          c.systemOne(REQUEST);
        }
        String all = records.stream().map(r -> r.getMessage()).reduce("", (a, b) -> a + "\n" + b);
        switch (level) {
          case OFF, WARNING -> assertThat(records).isEmpty();
          case INFO -> {
            assertThat(all)
                .contains(
                    "retrying in 500ms (retry 1/3) after JevInternalServerException status=500",
                    "status=502",
                    "status=503");
            assertThat(all)
                .doesNotContain("review-raw-body-marker", "json-marker-7", "known-msg-marker");
          }
          case DEBUG ->
              assertThat(all)
                  .contains("review-raw-body-marker", "json-marker-7", "known-msg-marker");
          default -> throw new AssertionError(level);
        }
      }
    } finally {
      jul.removeHandler(handler);
      jul.setLevel(previous);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2 review R3: publication vs close(), delivery-thread identity
  // ---------------------------------------------------------------------------------------------

  /** Holds publications until released, then runs each on a fresh virtual thread. */
  private static final class HoldingDelivery implements java.util.concurrent.Executor {
    final List<Runnable> held = new java.util.concurrent.CopyOnWriteArrayList<>();
    volatile boolean open;

    @Override
    public void execute(Runnable r) {
      if (open) {
        Thread.startVirtualThread(r);
      } else {
        held.add(r);
      }
    }

    void release() {
      open = true;
      held.forEach(Thread::startVirtualThread);
      held.clear();
    }
  }

  private static ExecutorService blockedCallerExecutor(CountDownLatch occupied) {
    ExecutorService single = Executors.newSingleThreadExecutor(r -> new Thread(r, "caller-exec"));
    single.submit(() -> occupied.await(10, TimeUnit.SECONDS));
    return single;
  }

  private static HttpClient callerHttp() {
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  }

  /** R3 P2(1), deterministic: close() must not return until queued results are published. */
  @ParameterizedTest(name = "grace={0}ms")
  @CsvSource({"0", "50"})
  void closeWaitsForPublicationButNotForCallbacks(long graceMillis) throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    JevClient c =
        client()
            .executor(single)
            .httpClient(callerHttp())
            .noDeadline()
            .closeGracePeriod(Duration.ofMillis(graceMillis))
            .delivery(delivery)
            .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    Thread closer = new Thread(c::close, "closer");
    closer.start();
    try {
      closer.join(300);
      assertThat(closer.isAlive()).as("close() waits while publication is held").isTrue();
      assertThat(queued.isDone()).isFalse();
      assertThat(delivery.held).hasSize(1);
      delivery.release();
      closer.join(2000);
      assertThat(closer.isAlive()).as("close() returns once the result is published").isFalse();
      assertThat(queued.isDone()).isTrue();
      assertThat(queued.isCancelled()).isTrue();
    } finally {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  /** R3 P2(1), the reviewer's probe with real delivery: never pending after close returns. */
  @Test
  void resultsAreAlwaysTerminalWhenCloseReturns() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpClient http = callerHttp();
    int pending = 0;
    try {
      for (int trial = 0; trial < 200; trial++) {
        JevClient c =
            client()
                .executor(single)
                .httpClient(http)
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
        CompletableFuture<ModelList> f = c.modelsAsync();
        c.close();
        if (!f.isDone()) {
          pending++;
        }
        assertThat(f.isCancelled()).isTrue();
      }
    } finally {
      occupied.countDown();
      single.shutdownNow();
    }
    assertThat(pending).as("pending after close, out of 200 trials").isZero();
    assertThat(server.requests()).isEmpty();
  }

  /**
   * Registers a callback while publication is held, releases, and returns the callback's thread.
   */
  private static Thread callbackThread(CompletableFuture<?> f, HoldingDelivery delivery)
      throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (delivery.held.isEmpty() && System.nanoTime() < end) {
      Thread.sleep(2); // wait for the outcome to reach the (held) delivery seam
    }
    assertThat(delivery.held).as("publication is held").hasSize(1);
    AtomicReference<Thread> thread = new AtomicReference<>();
    CompletableFuture<?> stage = f.whenComplete((r, t) -> thread.set(Thread.currentThread()));
    delivery.release();
    stage.handle((r, t) -> null).get(3, TimeUnit.SECONDS); // the dependent stage: callback has run
    return thread.get();
  }

  private static void assertSdkDeliveryThread(Thread t) {
    assertThat(t).isNotNull();
    assertThat(t.isVirtual()).as("published on a virtual thread: " + t).isTrue();
    assertThat(t.getName()).doesNotContain("jev-deadline", "caller-exec", "closer", "main");
  }

  /**
   * R3 P2(2): every completion (deadline, failure, success) is published on an SDK virtual thread.
   */
  @Test
  void completionsArePublishedOnSdkVirtualThreadsForEveryOutcome() throws Exception {
    server.enqueueJson(500, "{}").enqueueJson(200, MODELS);
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService blocked = blockedCallerExecutor(occupied);
    ExecutorService caller = Executors.newFixedThreadPool(2, r -> new Thread(r, "caller-exec"));
    try {
      HoldingDelivery d1 = new HoldingDelivery();
      try (JevClient c =
          client().executor(blocked).deadline(Duration.ofMillis(100)).delivery(d1).build()) {
        CompletableFuture<ModelList> f = c.modelsAsync();
        assertSdkDeliveryThread(callbackThread(f, d1));
        assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
            .hasCauseInstanceOf(JevDeadlineExceededException.class);
      }
      HoldingDelivery d2 = new HoldingDelivery();
      try (JevClient c =
          client().executor(caller).retryPolicy(RetryPolicy.NONE).delivery(d2).build()) {
        CompletableFuture<ModelList> f1 = c.modelsAsync();
        assertSdkDeliveryThread(callbackThread(f1, d2));
        assertThatThrownBy(() -> f1.get(1, TimeUnit.SECONDS))
            .hasCauseInstanceOf(JevInternalServerException.class);
      }
      HoldingDelivery d3 = new HoldingDelivery();
      try (JevClient c =
          client().executor(caller).retryPolicy(RetryPolicy.NONE).delivery(d3).build()) {
        CompletableFuture<ModelList> f2 = c.modelsAsync();
        assertSdkDeliveryThread(callbackThread(f2, d3));
        assertThat(f2.get(1, TimeUnit.SECONDS).models()).hasSize(2);
      }
    } finally {
      occupied.countDown();
      blocked.shutdownNow();
      caller.shutdownNow();
    }
  }

  /**
   * R3 P2(2): a delivery that rejects, or a close() in the hand-off gap, never runs callbacks
   * inline.
   */
  @Test
  void rejectedDeliveryAndCloseInTheHandoffGapStillPublishOnVirtualThreads() throws Exception {
    // A delivery executor that rejects once released: the SDK must fall back to a fresh virtual
    // thread. The callback is registered before the rejection can happen.
    server.enqueueJson(200, MODELS);
    CountDownLatch rejectGate = new CountDownLatch(1);
    CountDownLatch reachedDelivery = new CountDownLatch(1);
    AtomicReference<Thread> rejectedPath = new AtomicReference<>();
    try (JevClient c =
        client()
            .delivery(
                r -> {
                  reachedDelivery.countDown();
                  try {
                    rejectGate.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  throw new java.util.concurrent.RejectedExecutionException("test");
                })
            .build()) {
      CompletableFuture<ModelList> f = c.modelsAsync();
      assertThat(reachedDelivery.await(3, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<?> stage =
          f.whenComplete((r, t) -> rejectedPath.set(Thread.currentThread()));
      rejectGate.countDown();
      stage.get(3, TimeUnit.SECONDS);
      assertThat(f.get(1, TimeUnit.SECONDS).models()).hasSize(2);
    }
    assertSdkDeliveryThread(rejectedPath.get());

    // close() while a deadline expiry sits in the gap between finish() and hand-off.
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService blocked = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    AtomicReference<Thread> gapPath = new AtomicReference<>();
    JevClient c =
        client()
            .executor(blocked)
            .httpClient(callerHttp())
            .deadline(Duration.ofMillis(50))
            .closeGracePeriod(Duration.ZERO)
            .delivery(delivery)
            .build();
    try {
      CompletableFuture<ModelList> f = c.modelsAsync();
      CompletableFuture<?> gapStage = f.whenComplete((r, t) -> gapPath.set(Thread.currentThread()));
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (delivery.held.isEmpty() && System.nanoTime() < end) {
        Thread.sleep(5); // the timer has won finish() and handed off; publication is held
      }
      assertThat(delivery.held).hasSize(1);
      Thread closer = new Thread(c::close, "closer");
      closer.start();
      closer.join(200);
      assertThat(closer.isAlive()).as("close() waits for the held publication").isTrue();
      delivery.release();
      closer.join(2000);
      assertThat(closer.isAlive()).isFalse();
      assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
          .hasCauseInstanceOf(JevDeadlineExceededException.class);
      gapStage.handle((r, t) -> null).get(3, TimeUnit.SECONDS);
      assertSdkDeliveryThread(gapPath.get());
    } finally {
      delivery.release();
      occupied.countDown();
      blocked.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2 review R4: cancellation releases tracking; publication timeout is observable
  // ---------------------------------------------------------------------------------------------

  private static void drain(ExecutorService executor) throws Exception {
    executor.submit(() -> {}).get(5, TimeUnit.SECONDS); // barrier: everything queued before has run
  }

  /** R4 P2(1): explicitly cancelled calls must not stay tracked, whatever state they were in. */
  @Test
  void explicitCancellationReleasesTrackingInEveryState() throws Exception {
    // (a) 100 cancellations before start on a blocked caller-owned executor (the reviewer's probe).
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
    List<CompletableFuture<ModelList>> futures = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      futures.add(c.modelsAsync());
    }
    futures.forEach(f -> f.cancel(true));
    futures.forEach(f -> assertThat(f.isCancelled()).isTrue());
    assertThat(c.trackedCalls()).as("after 100 cancellations").isZero();
    futures.get(0).cancel(true); // repeated cancellation is harmless
    occupied.countDown();
    drain(single);
    assertThat(c.trackedCalls()).as("after the worker drained").isZero();
    c.close();
    assertThat(c.trackedCalls()).as("after close").isZero();
    assertThat(server.requests()).isEmpty();
    single.shutdownNow();

    // (b) during an HTTP exchange.
    server.enqueue(TestServer.Scripted.json(200, OK).stallingHeaders(Duration.ofSeconds(5)));
    try (HttpJevClient c2 =
        (HttpJevClient) client().timeout(Duration.ofSeconds(10)).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c2.systemOneAsync(REQUEST);
      awaitRequests(1);
      f.cancel(true);
      assertThat(c2.trackedCalls()).as("during HTTP").isZero();
    }

    // (c) during backoff, with a blocking cancellation callback, cancelled from another thread.
    server.enqueueJson(500, "{}").enqueueJson(200, OK);
    CountDownLatch sleeping = new CountDownLatch(1);
    Sleeper blockingSleep =
        (delay, handle) -> {
          sleeping.countDown();
          return !handle.awaitCancel(Duration.ofSeconds(10));
        };
    CountDownLatch callbackStarted = new CountDownLatch(1);
    CountDownLatch callbackRelease = new CountDownLatch(1);
    try (HttpJevClient c3 = (HttpJevClient) client().sleeper(blockingSleep).noDeadline().build()) {
      CompletableFuture<SystemOneResponse> f = c3.systemOneAsync(REQUEST);
      f.whenComplete(
          (r, t) -> {
            callbackStarted.countDown();
            try {
              callbackRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertThat(sleeping.await(3, TimeUnit.SECONDS)).isTrue();
      Thread canceller = new Thread(() -> f.cancel(true), "canceller");
      canceller.start();
      assertThat(callbackStarted.await(3, TimeUnit.SECONDS)).isTrue();
      assertThat(f.isCancelled()).isTrue();
      callbackRelease.countDown();
      canceller.join(3000);
      assertThat(c3.trackedCalls())
          .as("during backoff, after the cancel callback returned")
          .isZero();
    } finally {
      callbackRelease.countDown();
    }

    // (d) cancellation racing close().
    CountDownLatch occupied2 = new CountDownLatch(1);
    ExecutorService single2 = blockedCallerExecutor(occupied2);
    HttpJevClient c4 =
        (HttpJevClient)
            client()
                .executor(single2)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .build();
    List<CompletableFuture<ModelList>> racing = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      racing.add(c4.modelsAsync());
    }
    Thread closer = new Thread(c4::close, "closer");
    closer.start();
    racing.forEach(f -> f.cancel(true));
    closer.join(5000);
    assertThat(closer.isAlive()).isFalse();
    racing.forEach(f -> assertThat(f.isDone()).isTrue());
    // Entries that close() won are released by their delivery thread right after publication (and
    // after any callbacks), so the set drains a few microseconds after close() returns.
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (c4.trackedCalls() != 0 && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    assertThat(c4.trackedCalls()).as("after cancel/close race").isZero();
    occupied2.countDown();
    single2.shutdownNow();
  }

  /** R4 P2(2): if publication cannot be established within its timeout, close() throws. */
  @Test
  void publicationTimeoutMakesCloseThrowAfterShuttingDownOwnedResources() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery(); // never released until close has returned
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single) // caller-owned: must not be shut down
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .publicationTimeout(Duration.ofMillis(150))
                .delivery(delivery)
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    long start = System.nanoTime();
    try {
      assertThatThrownBy(c::close)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("1 result(s) still unpublished (timed_out")
          .hasMessageContaining("publication timeout 150 ms");
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      assertThat(elapsed).isBetween(Duration.ofMillis(150), Duration.ofSeconds(2));
      assertThat(queued.isDone()).as("the guarantee was not met, and close said so").isFalse();
      // shutdownNow() was issued with a zero grace budget; termination itself is asynchronous.
      assertThat(c.config().httpClient().awaitTermination(Duration.ofSeconds(2)))
          .as("owned HttpClient shut down anyway")
          .isTrue();
      assertThat(single.isShutdown()).as("caller-owned executor untouched").isFalse();
      // A second close while publication is still held must not report success either.
      assertThatThrownBy(c::close)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("1 result(s) still unpublished (timed_out");
    } finally {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
    assertThatThrownBy(() -> queued.get(2, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
    c.close(); // once the result has been published, a later close returns normally and truthfully
    assertThat(queued.isDone()).isTrue();
  }

  /**
   * R4 P2(2): interrupting the closing thread during the publication wait throws and re-asserts.
   */
  @Test
  void interruptedPublicationWaitMakesCloseThrowAndReassertTheFlag() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .noDeadline()
                .closeGracePeriod(Duration.ZERO)
                .publicationTimeout(Duration.ofSeconds(10))
                .delivery(delivery)
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>(false);
    Thread closer =
        new Thread(
            () -> {
              try {
                c.close();
              } catch (Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
              }
            },
            "closer");
    closer.start();
    try {
      Thread.sleep(100); // inside the publication wait
      closer.interrupt();
      closer.join(3000);
      assertThat(closer.isAlive()).isFalse();
      assertThat(thrown.get()).isInstanceOf(JevException.class).hasMessageContaining("interrupted");
      assertThat(flag.get()).as("interrupt flag re-asserted").isTrue();
      assertThat(queued.isDone()).isFalse();
    } finally {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2 review R5: every close() that returns normally has verified the guarantee
  // ---------------------------------------------------------------------------------------------

  /** R5: a concurrent close() returns only after the first closer's shutdown, with results done. */
  @Test
  void concurrentCloseObservesTheFirstClosersOutcome() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ofMillis(600))
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    AtomicReference<Boolean> firstDoneWhenSecondReturned = new AtomicReference<>();
    AtomicReference<Boolean> resultDoneWhenSecondReturned = new AtomicReference<>();
    Thread first = new Thread(c::close, "first-closer");
    first.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!c.isClosed() && System.nanoTime() < end) {
      Thread.sleep(1); // admission closed: the first closer is now inside its grace wait
    }
    assertThat(c.isClosed()).isTrue();
    assertThat(first.isAlive()).isTrue();
    Thread second =
        new Thread(
            () -> {
              c.close();
              firstDoneWhenSecondReturned.set(!first.isAlive());
              resultDoneWhenSecondReturned.set(queued.isDone());
            },
            "second-closer");
    second.start();
    second.join(100);
    assertThat(second.isAlive()).as("second close waits for the first").isTrue();
    second.join(5000);
    first.join(5000);
    assertThat(second.isAlive()).isFalse();
    assertThat(resultDoneWhenSecondReturned.get())
        .as("result done when the second close returned")
        .isTrue();
    assertThat(firstDoneWhenSecondReturned.get()).as("first closer finished first").isTrue();
    assertThat(queued.isCancelled()).isTrue();
    occupied.countDown();
    single.shutdownNow();
  }

  /** R5: repeated close after a successful shutdown is a truthful no-op. */
  @Test
  void repeatedCloseAfterSuccessfulShutdownReturnsNormally() throws Exception {
    server.enqueueJson(200, MODELS);
    HttpJevClient c = (HttpJevClient) client().build();
    CompletableFuture<ModelList> f = c.modelsAsync();
    f.get(5, TimeUnit.SECONDS);
    c.close();
    c.close();
    c.close();
    assertThat(f.isDone()).isTrue();
    assertThat(c.trackedCalls()).isZero();
    assertThatThrownBy(c::models).isInstanceOf(IllegalStateException.class);
  }

  /**
   * R5: a concurrent closer that is interrupted throws and re-asserts, without touching shutdown.
   */
  @Test
  void interruptedConcurrentCloserThrowsAndReassertsTheFlag() throws Exception {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(Duration.ofMillis(600))
                .build();
    CompletableFuture<ModelList> queued = c.modelsAsync();
    Thread first = new Thread(c::close, "first-closer");
    first.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!c.isClosed() && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicReference<Boolean> flag = new AtomicReference<>(false);
    Thread second =
        new Thread(
            () -> {
              try {
                c.close();
              } catch (Throwable t) {
                thrown.set(t);
                flag.set(Thread.currentThread().isInterrupted());
              }
            },
            "second-closer");
    second.start();
    Thread.sleep(50);
    second.interrupt();
    second.join(2000);
    assertThat(second.isAlive()).isFalse();
    assertThat(thrown.get())
        .isInstanceOf(JevException.class)
        .hasMessageContaining("interrupted while waiting for shutdown");
    assertThat(flag.get()).as("interrupt flag re-asserted").isTrue();
    first.join(5000);
    assertThat(first.isAlive()).as("the first close was unaffected").isFalse();
    assertThat(queued.isCancelled()).isTrue();
    c.close(); // now everything is done: a truthful normal return
    occupied.countDown();
    single.shutdownNow();
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 2 review R6: a concurrent closer never exceeds one grace + publication budget
  // ---------------------------------------------------------------------------------------------

  /** Client with a queued call on a blocked caller executor and held publication. */
  private record HeldClose(
      HttpJevClient client,
      CompletableFuture<ModelList> queued,
      HoldingDelivery delivery,
      CountDownLatch occupied,
      ExecutorService single) {
    void cleanup() {
      delivery.release();
      occupied.countDown();
      single.shutdownNow();
    }
  }

  private HeldClose heldClose(Duration grace, Duration publicationTimeout) {
    CountDownLatch occupied = new CountDownLatch(1);
    ExecutorService single = blockedCallerExecutor(occupied);
    HoldingDelivery delivery = new HoldingDelivery();
    HttpJevClient c =
        (HttpJevClient)
            client()
                .executor(single)
                .httpClient(callerHttp())
                .noDeadline()
                .closeGracePeriod(grace)
                .publicationTimeout(publicationTimeout)
                .delivery(delivery)
                .build();
    return new HeldClose(c, c.modelsAsync(), delivery, occupied, single);
  }

  /** Starts the first closer and returns once it has closed admission (inside its waits). */
  private static Thread startFirstCloser(HttpJevClient c) throws Exception {
    Thread first =
        new Thread(
            () -> {
              try {
                c.close();
              } catch (JevException expected) {
                // publication held: the first closer reports the timeout
              }
            },
            "first-closer");
    first.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!c.isClosed() && System.nanoTime() < end) {
      Thread.sleep(1);
    }
    assertThat(c.isClosed()).isTrue();
    return first;
  }

  /** R6: overlapping closers, publication held past both — the second fails within ONE budget. */
  @ParameterizedTest(name = "grace={0}ms")
  @CsvSource({"0", "50"})
  void concurrentCloserSpendsAtMostOneGracePlusPublicationBudget(long graceMillis)
      throws Exception {
    Duration grace = Duration.ofMillis(graceMillis);
    Duration publication = Duration.ofMillis(400);
    HeldClose h = heldClose(grace, publication);
    try {
      Thread first = startFirstCloser(h.client());
      Thread.sleep(graceMillis + 50); // the first closer is ~50 ms into its publication wait
      long start = System.nanoTime();
      assertThatThrownBy(h.client()::close)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("still unpublished (timed_out");
      Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
      Duration budget = grace.plus(publication);
      assertThat(elapsed)
          .as("second close bounded by one grace + publication budget (plus scheduling tolerance)")
          .isLessThan(budget.plus(Duration.ofMillis(150)))
          .isGreaterThan(
              Duration.ofMillis(300)); // it did wait for the first closer, then for publication
      first.join(2000);
      assertThat(first.isAlive()).isFalse();
      assertThat(h.queued().isDone()).isFalse();
    } finally {
      h.cleanup();
    }
  }

  /** R6: publication arriving just before the second closer's deadline yields a normal return. */
  @Test
  void concurrentCloserReturnsNormallyWhenPublicationArrivesBeforeItsDeadline() throws Exception {
    HeldClose h = heldClose(Duration.ZERO, Duration.ofMillis(400));
    try {
      Thread first = startFirstCloser(h.client());
      Thread.sleep(50);
      AtomicReference<Throwable> secondOutcome = new AtomicReference<>();
      Thread second =
          new Thread(
              () -> {
                try {
                  h.client().close();
                } catch (Throwable t) {
                  secondOutcome.set(t);
                }
              },
              "second-closer");
      second.start();
      first.join(
          2000); // first closer times out at ~400 ms; second is now in its own remaining wait
      assertThat(second.isAlive()).isTrue();
      h.delivery().release(); // publish ~50 ms before the second closer's deadline
      second.join(2000);
      assertThat(second.isAlive()).isFalse();
      assertThat(secondOutcome.get()).as("second close returned normally").isNull();
      assertThat(h.queued().isDone()).isTrue();
    } finally {
      h.cleanup();
    }
  }

  /** R6: interrupting the second closer during its publication remainder throws and re-asserts. */
  @Test
  void concurrentCloserInterruptedDuringPublicationRemainderThrows() throws Exception {
    HeldClose h = heldClose(Duration.ZERO, Duration.ofSeconds(5));
    try {
      Thread first = startFirstCloser(h.client());
      Thread.sleep(50);
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      AtomicReference<Boolean> flag = new AtomicReference<>(false);
      Thread second =
          new Thread(
              () -> {
                try {
                  h.client().close();
                } catch (Throwable t) {
                  thrown.set(t);
                  flag.set(Thread.currentThread().isInterrupted());
                }
              },
              "second-closer");
      second.start();
      Thread.sleep(100);
      second.interrupt();
      second.join(2000);
      assertThat(second.isAlive()).isFalse();
      assertThat(thrown.get()).isInstanceOf(JevException.class).hasMessageContaining("interrupted");
      assertThat(flag.get()).as("interrupt flag re-asserted").isTrue();
      h.delivery().release();
      first.join(6000);
      assertThat(first.isAlive()).isFalse();
    } finally {
      h.cleanup();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 3 review R3: per-client log filtering covers the parser and observer paths
  // ---------------------------------------------------------------------------------------------

  /** Captures every record on the SDK's logger namespace, whichever class emitted it. */
  private static final class SdkLogCapture implements AutoCloseable {
    final List<java.util.logging.LogRecord> records =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.logging.Logger jul =
        java.util.logging.Logger.getLogger("net.codefinch.jev");
    private final java.util.logging.Level previous = jul.getLevel();
    private final java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(r);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };

    SdkLogCapture() {
      jul.setLevel(java.util.logging.Level.ALL);
      jul.addHandler(handler);
    }

    String messages() {
      return records.stream()
          .map(java.util.logging.LogRecord::getMessage)
          .reduce("", (a, b) -> a + "\n" + b);
    }

    @Override
    public void close() {
      jul.removeHandler(handler);
      jul.setLevel(previous);
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"OFF, false", "ERROR, false", "WARNING, true"})
  void unknownAnswerAndObserverWarningsHonourEachClientsLevel(String level, boolean expected)
      throws Exception {
    CallObserver throwing =
        new CallObserver() {
          @Override
          public void onCall(Call call) {
            throw new IllegalStateException("observer boom");
          }
        };
    CountDownLatch observed = new CountDownLatch(1);
    CallObserver sentinel =
        new CallObserver() {
          @Override
          public void onCall(Call call) {
            observed.countDown();
          }
        };
    try (SdkLogCapture capture = new SdkLogCapture()) {
      server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
      try (JevClient c =
          client()
              .logLevel(System.Logger.Level.valueOf(level))
              .observer(throwing)
              .observer(sentinel)
              .build()) {
        c.systemOne(REQUEST);
      }
      assertThat(observed.await(3, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(50); // the throwing observer's warning is logged on the delivery thread
      String all = capture.messages();
      if (expected) {
        assertThat(all)
            .as("rendered text carries the real id and type, not {0}/{1}")
            .contains("Ignoring answer 'mystery' with unrecognized type 'aurora'")
            .contains("CallObserver.onCall threw; ignoring");
        assertThat(capture.records).anyMatch(r -> r.getThrown() instanceof IllegalStateException);
      } else {
        assertThat(capture.records)
            .as(level + " logs nothing, parser and observer paths included")
            .isEmpty();
      }
    }
  }

  @Test
  void twoClientsWithDifferentLevelsFilterIndependently() throws Exception {
    try (SdkLogCapture capture = new SdkLogCapture()) {
      server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
      server.enqueueJson(200, Fixtures.read("responses/python-unknown-answer-type.json"));
      try (JevClient quiet = client().logLevel(System.Logger.Level.OFF).build();
          JevClient loud = client().logLevel(System.Logger.Level.INFO).build()) {
        quiet.systemOne(REQUEST);
        assertThat(capture.records).isEmpty();
        loud.systemOne(REQUEST);
      }
      assertThat(capture.messages()).contains("unrecognized type 'aurora'", "-> 200 in");
      assertThat(capture.records).hasSizeGreaterThanOrEqualTo(2);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Phase 3 review R3: attempt start is atomic with termination
  // ---------------------------------------------------------------------------------------------

  /**
   * Holds the worker at its very first clock read (the attempt-start timestamp, or the deadline
   * check just before it) while a terminal path fires. Either the attempt never starts, or its
   * event precedes the terminal one; the terminal event never comes first.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"cancel", "close", "deadline"})
  void terminalEventNeverPrecedesAnAttemptThatStarted(String path) throws Exception {
    server.enqueueJson(200, OK);
    Thread testThread = Thread.currentThread();
    CountDownLatch workerHeld = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Boolean> armed = new AtomicReference<>(false);
    java.util.function.LongSupplier holdingClock =
        () -> {
          Thread t = Thread.currentThread();
          if (t != testThread
              && !t.getName().equals("jev-deadline")
              && armed.compareAndSet(true, false)) {
            workerHeld.countDown();
            try {
              release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              t.interrupt();
            }
          }
          return System.nanoTime();
        };
    List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
    CountDownLatch terminal = new CountDownLatch(1);
    CallObserver ordering =
        new CallObserver() {
          @Override
          public void onAttempt(Attempt attempt) {
            order.add("attempt" + attempt.attempt());
          }

          @Override
          public void onCall(Call call) {
            order.add("call:" + call.outcome());
            terminal.countDown();
          }
        };
    JevClientBuilder b =
        client().nanoTime(holdingClock).observer(ordering).closeGracePeriod(Duration.ZERO);
    JevClient c =
        (path.equals("deadline") ? b.deadline(Duration.ofMillis(200)) : b.noDeadline()).build();
    armed.set(true);
    CompletableFuture<SystemOneResponse> f = c.systemOneAsync(REQUEST);
    assertThat(workerHeld.await(3, TimeUnit.SECONDS))
        .as("worker held at its first clock read")
        .isTrue();
    switch (path) {
      case "cancel" -> f.cancel(true);
      case "close" -> c.close();
      default -> Thread.sleep(300); // let the deadline expire while the worker is held
    }
    assertThat(f.isDone()).as("the public result is terminal while the worker is held").isTrue();
    release.countDown();
    assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
    Thread.sleep(100); // any late attempt event would arrive now
    String expected = path.equals("deadline") ? "call:DEADLINE" : "call:CANCELLED";
    assertThat(order).as(path).isIn(List.of(expected), List.of("attempt1", expected));
    c.close();
  }

  private void awaitRequests(int n) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (server.requests().size() < n && System.nanoTime() < end) {
      Thread.sleep(10);
    }
    assertThat(server.requests()).hasSizeGreaterThanOrEqualTo(n);
  }
}
