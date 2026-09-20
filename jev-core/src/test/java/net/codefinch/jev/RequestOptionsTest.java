package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestOptionsTest {

  @Test
  void builderAndValidation() {
    RequestOptions o =
        RequestOptions.builder()
            .timeout(Duration.ofSeconds(2))
            .deadline(Duration.ofSeconds(9))
            .header("X-Call", "1")
            .build();
    assertThat(o.timeout()).contains(Duration.ofSeconds(2));
    assertThat(o.deadline()).contains(Duration.ofSeconds(9));
    assertThat(o.headers().get("x-call")).isEqualTo("1");
    assertThat(RequestOptions.NONE.timeout()).isEmpty();
    assertThat(RequestOptions.NONE.headers()).isEmpty();
    assertThatThrownBy(() -> RequestOptions.builder().timeout(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout must be positive");
    assertThatThrownBy(
            () ->
                new RequestOptions(
                    Optional.empty(),
                    Optional.of(Duration.ofSeconds(-1)),
                    Map.of(),
                    Optional.empty()))
        .hasMessageContaining("deadline must not be negative");
    assertThatThrownBy(() -> o.headers().put("y", "z"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** Review P2: the plan's "zero disables the deadline" needs a per-call representation. */
  @Test
  void deadlineHasThreeStatesInheritDisabledAndSet() {
    assertThat(RequestOptions.NONE.deadline()).isEmpty();
    assertThat(RequestOptions.NONE.deadlineDisabled()).isFalse();
    RequestOptions disabled = RequestOptions.builder().noDeadline().build();
    assertThat(disabled.deadline()).contains(Duration.ZERO);
    assertThat(disabled.deadlineDisabled()).isTrue();
    assertThat(RequestOptions.builder().deadline(Duration.ZERO).build().deadlineDisabled())
        .isTrue();
    RequestOptions set = RequestOptions.builder().deadline(Duration.ofSeconds(5)).build();
    assertThat(set.deadline()).contains(Duration.ofSeconds(5));
    assertThat(set.deadlineDisabled()).isFalse();
    // Per-attempt timeouts stay strictly positive: zero is not a valid "disabled" there.
    assertThatThrownBy(() -> RequestOptions.builder().timeout(Duration.ZERO).build())
        .hasMessageContaining("timeout must be positive");
  }

  @Test
  void responseMetadataIsCaseInsensitiveAndExtractsRequestId() {
    ResponseMetadata m =
        ResponseMetadata.of(
            Map.of("X-TypeSafe-Request-Id", List.of("r1", "r2"), "A", List.of("b")), "{}");
    assertThat(m.requestId()).contains("r1");
    assertThat(m.headers().get("a")).containsExactly("b");
    assertThat(m.rawBody()).isEqualTo("{}");
    assertThat(ResponseMetadata.of(Map.of("x-typesafe-request-id", List.of()), "").requestId())
        .isEmpty();
    assertThat(ResponseMetadata.of(Map.of(), "").requestId()).isEmpty();
    ModelList list = new ModelList(List.of(new ModelMetadata("n", "d", "2026-01-01")), m);
    assertThat(list.requestId()).contains("r1");
    assertThatThrownBy(() -> list.models().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
