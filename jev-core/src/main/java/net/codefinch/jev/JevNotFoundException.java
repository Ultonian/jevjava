package net.codefinch.jev;

import java.util.List;
import java.util.Map;

/** HTTP 404: not found. Mirrors upstream {@code TypeSafeNotFoundError}. */
public class JevNotFoundException extends JevApiException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception; the message is extracted from the body. */
  public JevNotFoundException(Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(404, headers, rawBody, endpoint, null);
  }
}
