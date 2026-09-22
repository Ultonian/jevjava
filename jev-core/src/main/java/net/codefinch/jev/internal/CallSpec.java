package net.codefinch.jev.internal;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What to send and how to read the answer; independent of retries and timing. */
record CallSpec<T>(
    String operation,
    String method,
    URI uri,
    String endpoint,
    Optional<String> body,
    Parser<T> parser) {
  interface Parser<T> {
    T parse(int status, Map<String, List<String>> headers, String body, String endpoint);
  }
}
