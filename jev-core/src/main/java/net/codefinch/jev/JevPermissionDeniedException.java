package net.codefinch.jev;

import java.util.List;
import java.util.Map;

/**
 * HTTP 403: permission denied. Note that a request with no API key at all has been observed to
 * return 403 (with {@code error_type: authentication_error}) rather than 401, so check the key
 * first. Mirrors upstream {@code TypeSafePermissionDeniedError}.
 */
public class JevPermissionDeniedException extends JevApiException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception; the message is extracted from the body. */
  public JevPermissionDeniedException(
      Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(403, headers, rawBody, endpoint, null);
  }
}
