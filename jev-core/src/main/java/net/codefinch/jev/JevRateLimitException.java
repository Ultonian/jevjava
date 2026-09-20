package net.codefinch.jev;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.codefinch.jev.internal.RetryAfter;

/**
 * HTTP 429: the account's rate limit was exceeded. Exposes the server-suggested delay from {@code
 * retry-after-ms} (preferred) or {@code retry-after}, if either is present and valid. Mirrors
 * upstream {@code TypeSafeRateLimitError}.
 */
public class JevRateLimitException extends JevApiException {
  private static final long serialVersionUID = 1L;

  private final Duration retryAfter;

  /** Creates the exception; the message is extracted from the body. */
  public JevRateLimitException(Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(429, headers, rawBody, endpoint, null);
    this.retryAfter = RetryAfter.parse(headers == null ? Map.of() : headers).orElse(null);
  }

  /** The delay the server asked for, if any. */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
