package net.codefinch.jev.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import net.codefinch.jev.RequestOptions;
import net.codefinch.jev.RetryPolicy;
import org.junit.jupiter.api.Test;

class CallOptionsTest {
  @Test
  void retryOverridesPreserveUnspecifiedClientSettings() {
    RetryPolicy client = RetryPolicy.DEFAULT.withMaxRetries(5);
    RequestOptions partial = RequestOptions.builder().retry(p -> p.withBackoffJitter(0)).build();
    RetryPolicy resolved = CallOptions.resolveRetry(partial, client);
    assertThat(resolved.maxRetries()).isEqualTo(5);
    assertThat(resolved.backoffJitter()).isZero();
    assertThat(CallOptions.resolveRetry(RequestOptions.NONE, client)).isSameAs(client);
    assertThat(
            CallOptions.resolveRetry(
                RequestOptions.builder().retry(p -> RetryPolicy.NONE).build(), client))
        .isSameAs(RetryPolicy.NONE);
    assertThatThrownBy(
            () ->
                CallOptions.resolveRetry(RequestOptions.builder().retry(p -> null).build(), client))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("retry override");
  }

  @Test
  void deadlineCanInheritDisableOrReplaceTheClientDefault() {
    Optional<Duration> client = Optional.of(Duration.ofSeconds(10));
    assertThat(CallOptions.resolveDeadline(RequestOptions.NONE, client)).isEqualTo(client);
    assertThat(CallOptions.resolveDeadline(RequestOptions.NONE, Optional.empty())).isEmpty();
    assertThat(CallOptions.resolveDeadline(RequestOptions.builder().noDeadline().build(), client))
        .isEmpty();
    assertThat(
            CallOptions.resolveDeadline(
                RequestOptions.builder().deadline(Duration.ZERO).build(), client))
        .isEmpty();
    RequestOptions override = RequestOptions.builder().deadline(Duration.ofSeconds(2)).build();
    assertThat(CallOptions.resolveDeadline(override, client)).contains(Duration.ofSeconds(2));
    assertThat(CallOptions.resolveDeadline(override, Optional.empty()))
        .contains(Duration.ofSeconds(2));
  }
}
