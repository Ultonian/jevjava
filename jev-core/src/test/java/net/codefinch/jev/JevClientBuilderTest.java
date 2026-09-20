package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import net.codefinch.jev.internal.ClientConfig;
import net.codefinch.jev.internal.HttpJevClient;
import org.junit.jupiter.api.Test;

class JevClientBuilderTest {

  private static ClientConfig build(
      Map<String, String> env, java.util.function.UnaryOperator<JevClientBuilder> more) {
    JevClientBuilder b = JevClient.builder().env(env::get);
    try (HttpJevClient c = (HttpJevClient) more.apply(b).build()) {
      return c.config();
    }
  }

  @Test
  void defaultsWhenOnlyTheKeyIsSet() {
    ClientConfig c = build(Map.of("TYPESAFE_API_KEY", " k "), b -> b);
    assertThat(c.apiKey()).isEqualTo("k");
    assertThat(c.baseUrl()).isEqualTo(URI.create("https://api.typesafe.ai"));
    assertThat(c.defaultModel()).isEqualTo("jev-latest");
    assertThat(c.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.deadline()).contains(Duration.ofSeconds(30));
    assertThat(c.retry()).isEqualTo(RetryPolicy.DEFAULT);
    assertThat(c.closeGracePeriod()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.publicationTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(c.logLevel()).isEqualTo(Level.INFO);
    assertThat(c.ownsHttpClient()).isTrue();
    assertThat(c.ownsExecutor()).isTrue();
    assertThat(c.defaultHeaders()).isEmpty();
  }

  @Test
  void environmentIsTrimmedAndBlankMeansUnset() {
    Map<String, String> env = new HashMap<>();
    env.put("TYPESAFE_API_KEY", "k");
    env.put("TYPESAFE_BASE_URL", "  https://api.example.test/prefix/  ");
    env.put("TYPESAFE_DEFAULT_MODEL", "   ");
    env.put("TYPESAFE_LOG_LEVEL", " DEBUG ");
    ClientConfig c = build(env, b -> b);
    assertThat(c.baseUrl()).isEqualTo(URI.create("https://api.example.test/prefix"));
    assertThat(c.defaultModel()).isEqualTo("jev-latest");
    assertThat(c.logLevel()).isEqualTo(Level.DEBUG);
  }

  @Test
  void explicitValuesBeatTheEnvironment() {
    Map<String, String> env =
        Map.of(
            "TYPESAFE_API_KEY",
            "envkey",
            "TYPESAFE_BASE_URL",
            "https://env",
            "TYPESAFE_DEFAULT_MODEL",
            "env-model",
            "TYPESAFE_LOG_LEVEL",
            "error");
    ClientConfig c =
        build(
            env,
            b ->
                b.apiKey("k")
                    .baseUrl(URI.create("https://explicit///"))
                    .defaultModel("jev-1.13.0")
                    .timeout(Duration.ofSeconds(3))
                    .noDeadline()
                    .retryPolicy(RetryPolicy.NONE)
                    .defaultHeaders(Map.of("X-A", "1"))
                    .closeGracePeriod(Duration.ofSeconds(1))
                    .logLevel(Level.WARNING));
    assertThat(c.apiKey()).isEqualTo("k");
    assertThat(c.baseUrl()).isEqualTo(URI.create("https://explicit"));
    assertThat(c.defaultModel()).isEqualTo("jev-1.13.0");
    assertThat(c.timeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(c.deadline()).isEmpty();
    assertThat(c.retry()).isEqualTo(RetryPolicy.NONE);
    assertThat(c.defaultHeaders().get("x-a")).isEqualTo("1"); // case-insensitive
    assertThat(c.closeGracePeriod()).isEqualTo(Duration.ofSeconds(1));
    assertThat(c.logLevel()).isEqualTo(Level.WARNING);
  }

  @Test
  void logLevelNamesFromTheEnvironment() {
    for (Map.Entry<String, Level> e :
        Map.of(
                "trace",
                Level.TRACE,
                "info",
                Level.INFO,
                "warn",
                Level.WARNING,
                "warning",
                Level.WARNING,
                "error",
                Level.ERROR,
                "off",
                Level.OFF)
            .entrySet()) {
      assertThat(
              build(Map.of("TYPESAFE_API_KEY", "k", "TYPESAFE_LOG_LEVEL", e.getKey()), b -> b)
                  .logLevel())
          .isEqualTo(e.getValue());
    }
    assertThatThrownBy(
            () -> build(Map.of("TYPESAFE_API_KEY", "k", "TYPESAFE_LOG_LEVEL", "loud"), b -> b))
        .isInstanceOf(JevException.class)
        .hasMessageContaining("TYPESAFE_LOG_LEVEL");
  }

  @Test
  void missingKeyNamesTheVariable() {
    assertThatThrownBy(() -> build(Map.of(), b -> b))
        .isInstanceOf(JevException.class)
        .hasMessage(
            "No API key was provided. Pass apiKey or set the TYPESAFE_API_KEY environment"
                + " variable.");
    assertThatThrownBy(() -> build(Map.of("TYPESAFE_API_KEY", "  "), b -> b))
        .isInstanceOf(JevException.class);
  }

  @Test
  void invalidSettingsAreRejected() {
    Map<String, String> env = Map.of("TYPESAFE_API_KEY", "k");
    assertThatThrownBy(() -> build(env, b -> b.timeout(Duration.ZERO)))
        .hasMessageContaining("timeout must be positive");
    assertThatThrownBy(() -> build(env, b -> b.deadline(Duration.ofSeconds(-1))))
        .hasMessageContaining("deadline must not be negative");
    assertThatThrownBy(() -> build(env, b -> b.publicationTimeout(Duration.ofSeconds(-1))))
        .hasMessageContaining("publicationTimeout");
    assertThatThrownBy(() -> build(env, b -> b.closeGracePeriod(Duration.ofSeconds(-1))))
        .hasMessageContaining("closeGracePeriod");
    assertThatThrownBy(() -> build(env, b -> b.defaultModel(" ")))
        .hasMessageContaining("defaultModel");
  }

  @Test
  void fromEnvUsesTheRealEnvironmentVariables() {
    if (System.getenv("TYPESAFE_API_KEY") == null) {
      assertThatThrownBy(JevClient::fromEnv)
          .isInstanceOf(JevException.class)
          .hasMessageContaining("TYPESAFE_API_KEY");
    } else {
      try (JevClient c = JevClient.fromEnv()) {
        assertThat(c).isNotNull();
      }
    }
  }
}
