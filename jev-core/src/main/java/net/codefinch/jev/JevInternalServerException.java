package net.codefinch.jev;

import java.util.List;
import java.util.Map;

/**
 * Any HTTP 5xx, including 529 (overloaded). Upstream has no dedicated overloaded class, so neither
 * does this SDK; use {@link #isOverloaded()} or {@link #status()}. Mirrors upstream {@code
 * TypeSafeInternalServerError}.
 */
public class JevInternalServerException extends JevApiException {
  private static final long serialVersionUID = 1L;

  /** The status the API documents as "temporarily overloaded". */
  public static final int OVERLOADED = 529;

  /** Creates the exception; the message is extracted from the body. */
  public JevInternalServerException(
      int status, Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(status, headers, rawBody, endpoint, null);
  }

  /** True for HTTP 529. */
  public boolean isOverloaded() {
    return status() == OVERLOADED;
  }
}
