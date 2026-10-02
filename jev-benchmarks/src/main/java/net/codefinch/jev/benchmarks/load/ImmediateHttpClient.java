package net.codefinch.jev.benchmarks.load;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.LongAdder;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import net.codefinch.jev.benchmarks.fixtures.Payloads;

/** Fixed synthetic exchange; consumes request bytes and invokes the actual response handler. */
final class ImmediateHttpClient extends HttpClient {
  static final URI ENDPOINT = URI.create("http://benchmark.invalid/v1/systemone");
  private static final HttpHeaders HEADERS =
      HttpHeaders.of(
          Map.of("Content-Type", List.of("application/json; charset=utf-8")), (a, b) -> true);
  private final byte[] response;
  private final LongAdder attempts = new LongAdder();
  private final LongAdder requestBytes = new LongAdder();

  ImmediateHttpClient() {
    response = Payloads.response("ticket").getBytes(StandardCharsets.UTF_8);
  }

  long attempts() {
    return attempts.sum();
  }

  long requestBytes() {
    return requestBytes.sum();
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    if (!request.uri().equals(ENDPOINT) || !request.method().equals("POST")) {
      throw new IllegalArgumentException("Only the fixed synthetic POST endpoint is supported");
    }
    attempts.increment();
    var consumed = HttpResponse.BodySubscribers.discarding();
    request.bodyPublisher().orElseThrow().subscribe(bridge(consumed, requestBytes));
    // This experiment deliberately accepts only immediately consumable request/response bodies.
    if (!consumed.getBody().toCompletableFuture().isDone()) {
      throw new IllegalStateException("Synthetic request did not complete immediately");
    }
    consumed.getBody().toCompletableFuture().join();
    var subscriber = handler.apply(new Info());
    HttpRequest.BodyPublishers.ofByteArray(response).subscribe(bridge(subscriber, null));
    var body = subscriber.getBody().toCompletableFuture();
    if (!body.isDone()) {
      throw new IllegalStateException("Synthetic response did not complete immediately");
    }
    return CompletableFuture.completedFuture(new Response<>(request, body.join()));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request,
      HttpResponse.BodyHandler<T> handler,
      HttpResponse.PushPromiseHandler<T> pushHandler) {
    throw new UnsupportedOperationException("No push in synthetic transport");
  }

  private static Flow.Subscriber<ByteBuffer> bridge(
      HttpResponse.BodySubscriber<?> subscriber, LongAdder bytes) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscriber.onSubscribe(subscription);
      }

      @Override
      public void onNext(ByteBuffer item) {
        if (bytes != null) {
          bytes.add(item.remaining());
        }
        subscriber.onNext(List.of(item.asReadOnlyBuffer()));
      }

      @Override
      public void onError(Throwable failure) {
        subscriber.onError(failure);
      }

      @Override
      public void onComplete() {
        subscriber.onComplete();
      }
    };
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
    return sendAsync(request, handler).join();
  }

  private record Info() implements HttpResponse.ResponseInfo {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpHeaders headers() {
      return HEADERS;
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }
  }

  private record Response<T>(HttpRequest request, T body) implements HttpResponse<T> {
    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpHeaders headers() {
      return HEADERS;
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }
  }

  @Override
  public Optional<CookieHandler> cookieHandler() {
    return Optional.empty();
  }

  @Override
  public Optional<Duration> connectTimeout() {
    return Optional.empty();
  }

  @Override
  public Redirect followRedirects() {
    return Redirect.NEVER;
  }

  @Override
  public Optional<ProxySelector> proxy() {
    return Optional.empty();
  }

  @Override
  public SSLContext sslContext() {
    throw new UnsupportedOperationException("No TLS");
  }

  @Override
  public SSLParameters sslParameters() {
    return new SSLParameters();
  }

  @Override
  public Optional<Authenticator> authenticator() {
    return Optional.empty();
  }

  @Override
  public Version version() {
    return Version.HTTP_1_1;
  }

  @Override
  public Optional<Executor> executor() {
    return Optional.empty();
  }
}
