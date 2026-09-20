package net.codefinch.jev;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Loads JSON fixtures from {@code src/test/resources/fixtures}. */
public final class Fixtures {
  public static final Map<String, List<String>> REQ_ID_HEADERS =
      Map.of(
          "X-TypeSafe-Request-Id", List.of("req-123"), "Content-Type", List.of("application/json"));

  private Fixtures() {}

  public static String read(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("no fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
