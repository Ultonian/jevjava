package net.codefinch.jev;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.codefinch.jev.internal.ErrorMessages;

/**
 * The server answered with an HTTP status outside 2xx, or with a 2xx body that failed validation.
 * Mirrors upstream {@code TypeSafeAPIError}.
 *
 * <p>The status, the response headers and the raw body are always kept verbatim, whatever their
 * shape; the message is extracted from the body on a best-effort basis (see {@link ErrorMessages}).
 */
public class JevApiException extends JevException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final TreeMap<String, List<String>> headers;
  private final String rawBody;
  private final String requestId;
  private final String endpoint;

  /**
   * Creates the exception.
   *
   * @param status HTTP status
   * @param headers response headers (copied, case-insensitive)
   * @param rawBody the body as received (may be empty)
   * @param endpoint {@code METHOD url} for the message, or null
   * @param message the message, or null to extract one from the body
   */
  public JevApiException(
      int status,
      Map<String, List<String>> headers,
      String rawBody,
      String endpoint,
      String message) {
    super(describe(status, rawBody, endpoint, message, requestIdOf(headers)));
    this.status = status;
    this.headers = copyHeaders(headers);
    this.rawBody = rawBody == null ? "" : rawBody;
    this.requestId = requestIdOf(headers);
    this.endpoint = endpoint;
  }

  /**
   * The subclass for an HTTP status, per the upstream mapping: 400, 401, 403, 404, 422, 429 have
   * their own classes, any 5xx is {@link JevInternalServerException}, anything else is this class.
   */
  public static JevApiException fromStatus(
      int status, Map<String, List<String>> headers, String rawBody, String endpoint) {
    return switch (status) {
      case 400 -> new JevBadRequestException(headers, rawBody, endpoint);
      case 401 -> new JevAuthenticationException(headers, rawBody, endpoint);
      case 403 -> new JevPermissionDeniedException(headers, rawBody, endpoint);
      case 404 -> new JevNotFoundException(headers, rawBody, endpoint);
      case 422 -> new JevUnprocessableEntityException(headers, rawBody, endpoint);
      case 429 -> new JevRateLimitException(headers, rawBody, endpoint);
      default ->
          status >= 500
              ? new JevInternalServerException(status, headers, rawBody, endpoint)
              : new JevApiException(status, headers, rawBody, endpoint, null);
    };
  }

  /** HTTP status code. */
  public int status() {
    return status;
  }

  /** Response headers, case-insensitive keys. Unmodifiable. */
  public Map<String, List<String>> headers() {
    return Collections.unmodifiableMap(headers);
  }

  /** The body exactly as received. */
  public String rawBody() {
    return rawBody;
  }

  /** The {@code x-typesafe-request-id} header, if present. */
  public Optional<String> requestId() {
    return Optional.ofNullable(requestId);
  }

  /** {@code METHOD url} of the failed request, if known. */
  public Optional<String> endpoint() {
    return Optional.ofNullable(endpoint);
  }

  private static String describe(
      int status, String rawBody, String endpoint, String message, String requestId) {
    String detail =
        message != null ? message : ErrorMessages.extract(rawBody == null ? "" : rawBody);
    StringBuilder sb = new StringBuilder();
    if (endpoint != null) {
      sb.append(endpoint).append(": ");
    }
    sb.append(status);
    if (detail != null && !detail.isEmpty()) {
      sb.append(' ').append(detail);
    } else if (message == null && (rawBody == null || rawBody.isEmpty())) {
      sb.append(" status code (no body)");
    }
    if (requestId != null) {
      sb.append(" (request_id=").append(requestId).append(')');
    }
    return sb.toString();
  }

  private static String requestIdOf(Map<String, List<String>> headers) {
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (ResponseMetadata.REQUEST_ID_HEADER.equalsIgnoreCase(e.getKey())
          && !e.getValue().isEmpty()) {
        return e.getValue().get(0);
      }
    }
    return null;
  }

  private static TreeMap<String, List<String>> copyHeaders(Map<String, List<String>> headers) {
    TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (headers != null) {
      headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
    }
    return copy;
  }
}
