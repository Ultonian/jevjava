package net.codefinch.jev.internal;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Replaces credential values in diagnostics. */
public final class Redaction {
  private static final Set<String> SECRET_HEADERS =
      Set.of(
          "authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie");

  /** Placeholder written in place of a secret. */
  public static final String REDACTED = "[redacted]";

  private Redaction() {}

  /**
   * Whether a header carries a credential: the upstream Python list plus any name containing {@code
   * token} or {@code secret}, case-insensitively.
   */
  public static boolean isSecret(String headerName) {
    String lower = headerName.toLowerCase(Locale.ROOT);
    return SECRET_HEADERS.contains(lower) || lower.contains("token") || lower.contains("secret");
  }

  /** Headers rendered as {@code {Name=value, ...}} with secret values replaced. */
  public static String headers(Map<String, ?> headers) {
    StringBuilder sb = new StringBuilder("{");
    headers.forEach(
        (name, value) -> {
          if (sb.length() > 1) {
            sb.append(", ");
          }
          sb.append(name).append('=').append(isSecret(name) ? REDACTED : value);
        });
    return sb.append('}').toString();
  }
}
