package net.codefinch.jev;

import java.util.List;
import java.util.Map;

/** HTTP 400: the request was malformed. Mirrors upstream {@code TypeSafeBadRequestError}. */
public class JevBadRequestException extends JevApiException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception; the message is extracted from the body. */
  public JevBadRequestException(
      Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(400, headers, rawBody, endpoint, null);
  }
}
