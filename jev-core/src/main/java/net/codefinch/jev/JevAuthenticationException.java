package net.codefinch.jev;

import java.util.List;
import java.util.Map;

/**
 * HTTP 401: the API key is invalid (observed live: a wrong key gives 401; a request with no key at
 * all gives 403, see {@link JevPermissionDeniedException}). Mirrors upstream {@code
 * TypeSafeAuthenticationError}.
 */
public class JevAuthenticationException extends JevApiException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception; the message is extracted from the body. */
  public JevAuthenticationException(
      Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(401, headers, rawBody, endpoint, null);
  }
}
