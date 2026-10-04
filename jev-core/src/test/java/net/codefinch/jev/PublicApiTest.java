package net.codefinch.jev;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import net.codefinch.jev.internal.HttpJevClient;
import net.codefinch.jev.model.Content;
import org.junit.jupiter.api.Test;

/** Guards against accidentally exposing credentials, test hooks or implementation helpers. */
class PublicApiTest {
  @Test
  void httpClientDoesNotPublishResolvedConfigurationOrLifecycleTestHooks() {
    assertThat(HttpJevClient.class.getMethods())
        .extracting(Method::getName)
        .doesNotContain("config", "trackedCalls", "isClosed", "isShutdownComplete");
  }

  @Test
  void contentAndOptionsExposeValuesRatherThanImplementationHelpers() {
    assertThat(Content.class.getClasses())
        .extracting(Class::getSimpleName)
        .containsExactlyInAnyOrder("Text", "JsonObject", "JsonArray", "Null");
    assertThat(Content.class.getMethods())
        .extracting(Method::getName)
        .doesNotContain("isContentType", "copy", "binary");
    assertThat(RequestOptions.class.getMethods())
        .extracting(Method::getName)
        .doesNotContain("resolveRetry", "deadlineDisabled");
  }
}
