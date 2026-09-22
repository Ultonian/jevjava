package net.codefinch.jev;

import static net.codefinch.jev.HttpTestFixture.MODELS;
import static net.codefinch.jev.HttpTestFixture.OK;
import static net.codefinch.jev.HttpTestFixture.QUESTIONS;
import static net.codefinch.jev.HttpTestFixture.REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpRequestTest {
  private HttpTestFixture fixture;
  private TestServer server;

  @BeforeEach
  void start() throws IOException {
    fixture = new HttpTestFixture();
    server = fixture.server;
  }

  @AfterEach
  void stop() {
    fixture.close();
  }

  private JevClientBuilder client() {
    return fixture.client();
  }

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
}
