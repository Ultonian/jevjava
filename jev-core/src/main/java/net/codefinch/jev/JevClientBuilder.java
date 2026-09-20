package net.codefinch.jev;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import net.codefinch.jev.internal.ClientConfig;
import net.codefinch.jev.internal.HttpJevClient;
import net.codefinch.jev.internal.Sleeper;

/**
 * Builds the HTTP {@link JevClient}. Unset values fall back to the environment ({@code
 * TYPESAFE_API_KEY}, {@code TYPESAFE_BASE_URL}, {@code TYPESAFE_DEFAULT_MODEL}, {@code
 * TYPESAFE_LOG_LEVEL}; values are trimmed and blank means unset) and then to the upstream defaults.
 */
public final class JevClientBuilder {

  /** Environment variable holding the API key. */
  public static final String API_KEY_ENV = "TYPESAFE_API_KEY";

  /** Environment variable overriding the base URL. */
  public static final String BASE_URL_ENV = "TYPESAFE_BASE_URL";

  /** Environment variable overriding the default model. */
  public static final String DEFAULT_MODEL_ENV = "TYPESAFE_DEFAULT_MODEL";

  /** Environment variable setting the minimum log level (debug, info, warn, error, off). */
  public static final String LOG_LEVEL_ENV = "TYPESAFE_LOG_LEVEL";

  /** Default API root. */
  public static final URI DEFAULT_BASE_URL = URI.create("https://api.typesafe.ai");

  /** Default model alias. */
  public static final String DEFAULT_MODEL = "jev-latest";

  /** Default per-attempt timeout (both upstream SDKs). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /** Default operation deadline (Java-only; Python's retry budget is also 30 s). */
  public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(30);

  /** Default time {@link JevClient#close()} waits for in-flight calls. */
  public static final Duration DEFAULT_CLOSE_GRACE = Duration.ofSeconds(10);

  /**
   * Default time {@link JevClient#close()} then waits for cancelled results to be published. In
   * practice publication takes microseconds; this bound only matters if the JVM cannot schedule a
   * virtual thread.
   */
  public static final Duration DEFAULT_PUBLICATION_TIMEOUT = Duration.ofSeconds(5);

  private String apiKey;
  private URI baseUrl;
  private String defaultModel;
  private Duration timeout = DEFAULT_TIMEOUT;
  private Duration deadline = DEFAULT_DEADLINE;
  private RetryPolicy retry = RetryPolicy.DEFAULT;
  private final Map<String, String> defaultHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private Duration closeGracePeriod = DEFAULT_CLOSE_GRACE;
  private Duration publicationTimeout = DEFAULT_PUBLICATION_TIMEOUT;
  private Level logLevel;
  private HttpClient httpClient;
  private ExecutorService executor;
  private Function<String, String> env = System::getenv;
  private LongSupplier nanoTime = System::nanoTime;
  private RandomGenerator random = RandomGenerator.getDefault();
  private Sleeper sleeper = Sleeper.REAL;
  private Executor delivery = Thread::startVirtualThread;

  JevClientBuilder() {}

  /** The API key; otherwise {@code TYPESAFE_API_KEY}. */
  public JevClientBuilder apiKey(String apiKey) {
    this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
    return this;
  }

  /** The API root; otherwise {@code TYPESAFE_BASE_URL}, then {@link #DEFAULT_BASE_URL}. */
  public JevClientBuilder baseUrl(URI baseUrl) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
    return this;
  }

  /** The model for requests without an override; otherwise {@code TYPESAFE_DEFAULT_MODEL}. */
  public JevClientBuilder defaultModel(String defaultModel) {
    this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
    return this;
  }

  /** Per-attempt timeout covering headers and body; strictly positive. */
  public JevClientBuilder timeout(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    return this;
  }

  /** Whole-operation deadline; {@link Duration#ZERO} disables it. */
  public JevClientBuilder deadline(Duration deadline) {
    this.deadline = Objects.requireNonNull(deadline, "deadline");
    return this;
  }

  /** Disables the operation deadline. */
  public JevClientBuilder noDeadline() {
    return deadline(Duration.ZERO);
  }

  /** The retry policy; default {@link RetryPolicy#DEFAULT}. */
  public JevClientBuilder retryPolicy(RetryPolicy retry) {
    this.retry = Objects.requireNonNull(retry, "retry");
    return this;
  }

  /** A header sent with every request (SDK-controlled headers cannot be overridden). */
  public JevClientBuilder defaultHeader(String name, String value) {
    defaultHeaders.put(
        Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
    return this;
  }

  /** Headers sent with every request. */
  public JevClientBuilder defaultHeaders(Map<String, String> headers) {
    headers.forEach(this::defaultHeader);
    return this;
  }

  /** How long {@link JevClient#close()} waits for in-flight calls before cancelling them. */
  public JevClientBuilder closeGracePeriod(Duration grace) {
    this.closeGracePeriod = Objects.requireNonNull(grace, "grace");
    return this;
  }

  /**
   * How long {@link JevClient#close()} waits, after the grace period, for the results of cancelled
   * calls to be published before giving up and throwing. See the interface for the contract.
   */
  public JevClientBuilder publicationTimeout(Duration timeout) {
    this.publicationTimeout = Objects.requireNonNull(timeout, "timeout");
    return this;
  }

  /** Minimum level this client logs at; otherwise {@code TYPESAFE_LOG_LEVEL}, then INFO. */
  public JevClientBuilder logLevel(Level level) {
    this.logLevel = Objects.requireNonNull(level, "level");
    return this;
  }

  /**
   * A caller-owned transport; never closed by the client. It must be built with {@link
   * HttpClient.Redirect#NEVER}: the SDK guarantees redirects are never followed (so the bearer
   * token is never forwarded), for injected transports as much as for its own.
   *
   * @throws JevException if the transport follows redirects
   */
  public JevClientBuilder httpClient(HttpClient httpClient) {
    Objects.requireNonNull(httpClient, "httpClient");
    if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new JevException(
          "httpClient must be built with followRedirects(Redirect.NEVER); got "
              + httpClient.followRedirects());
    }
    this.httpClient = httpClient;
    return this;
  }

  /** A caller-owned executor for async calls; never shut down by the client. */
  public JevClientBuilder executor(ExecutorService executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
    return this;
  }

  // Test seams (package-private).

  JevClientBuilder env(Function<String, String> env) {
    this.env = env;
    return this;
  }

  JevClientBuilder nanoTime(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
    return this;
  }

  JevClientBuilder random(RandomGenerator random) {
    this.random = random;
    return this;
  }

  JevClientBuilder sleeper(Sleeper sleeper) {
    this.sleeper = sleeper;
    return this;
  }

  JevClientBuilder delivery(Executor delivery) {
    this.delivery = delivery;
    return this;
  }

  /** Resolves the configuration and creates the client. */
  public JevClient build() {
    String key = apiKey != null ? apiKey : fromEnv(API_KEY_ENV);
    if (key == null || key.isBlank()) {
      throw new JevException(
          "No API key was provided. Pass apiKey or set the "
              + API_KEY_ENV
              + " environment variable.");
    }
    final URI base =
        baseUrl != null
            ? baseUrl
            : Optional.ofNullable(fromEnv(BASE_URL_ENV)).map(URI::create).orElse(DEFAULT_BASE_URL);
    String model =
        defaultModel != null
            ? defaultModel
            : Optional.ofNullable(fromEnv(DEFAULT_MODEL_ENV)).orElse(DEFAULT_MODEL);
    if (model.isBlank()) {
      throw new JevException("defaultModel must not be blank");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new JevException("timeout must be positive");
    }
    if (deadline.isNegative()) {
      throw new JevException("deadline must not be negative");
    }
    if (closeGracePeriod.isNegative()) {
      throw new JevException("closeGracePeriod must not be negative");
    }
    if (publicationTimeout.isNegative()) {
      throw new JevException("publicationTimeout must not be negative");
    }
    Level level = logLevel != null ? logLevel : parseLevel(fromEnv(LOG_LEVEL_ENV));
    boolean ownsHttp = httpClient == null;
    HttpClient http =
        ownsHttp
            ? HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            : httpClient;
    boolean ownsExecutor = executor == null;
    ExecutorService exec = ownsExecutor ? Executors.newVirtualThreadPerTaskExecutor() : executor;
    ClientConfig config =
        new ClientConfig(
            key.trim(),
            stripTrailingSlashes(base),
            model.trim(),
            timeout,
            deadline.isZero() ? Optional.empty() : Optional.of(deadline),
            retry,
            Collections.unmodifiableMap(caseInsensitiveCopy(defaultHeaders)),
            closeGracePeriod,
            publicationTimeout,
            level,
            http,
            ownsHttp,
            exec,
            ownsExecutor,
            nanoTime,
            random,
            sleeper,
            delivery);
    return new HttpJevClient(config);
  }

  private String fromEnv(String name) {
    String value = env.apply(name);
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Level parseLevel(String raw) {
    if (raw == null) {
      return Level.INFO;
    }
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "trace" -> Level.TRACE;
      case "debug" -> Level.DEBUG;
      case "info" -> Level.INFO;
      case "warn", "warning" -> Level.WARNING;
      case "error" -> Level.ERROR;
      case "off" -> Level.OFF;
      default ->
          throw new JevException(
              LOG_LEVEL_ENV + " must be one of debug, info, warn, error, off; got '" + raw + "'");
    };
  }

  /**
   * A copy that keeps case-insensitive keys ({@code new TreeMap<>(Map)} would drop the comparator).
   */
  private static Map<String, String> caseInsensitiveCopy(Map<String, String> headers) {
    Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    copy.putAll(headers);
    return copy;
  }

  private static URI stripTrailingSlashes(URI base) {
    String s = base.toString();
    int end = s.length();
    while (end > 0 && s.charAt(end - 1) == '/') {
      end--;
    }
    return URI.create(s.substring(0, end));
  }
}
