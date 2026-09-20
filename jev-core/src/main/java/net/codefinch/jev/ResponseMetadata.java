package net.codefinch.jev;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Transport-level facts about a successful response, kept for diagnostics: the request id, the
 * response headers (case-insensitive) and the raw body exactly as received.
 *
 * @param requestId the {@code x-typesafe-request-id} header, if the server sent one
 * @param headers all response headers, case-insensitive keys, unmodifiable
 * @param rawBody the body as received, before any parsing
 */
public record ResponseMetadata(
    Optional<String> requestId, Map<String, List<String>> headers, String rawBody) {

  /** Header carrying the server-side request id. */
  public static final String REQUEST_ID_HEADER = "x-typesafe-request-id";

  /** Validates and snapshots the components. */
  public ResponseMetadata {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(headers, "headers");
    Objects.requireNonNull(rawBody, "rawBody");
    headers = Collections.unmodifiableMap(copyHeaders(headers));
  }

  /** Metadata derived from headers and body, extracting the request id. */
  public static ResponseMetadata of(Map<String, List<String>> headers, String rawBody) {
    Map<String, List<String>> copy = Collections.unmodifiableMap(copyHeaders(headers));
    List<String> ids = copy.get(REQUEST_ID_HEADER);
    Optional<String> requestId =
        ids == null || ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    return new ResponseMetadata(requestId, copy, rawBody);
  }

  private static TreeMap<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    return copy;
  }
}
