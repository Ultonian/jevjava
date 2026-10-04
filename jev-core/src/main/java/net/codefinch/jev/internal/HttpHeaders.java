package net.codefinch.jev.internal;

import java.util.List;
import java.util.Map;

/** Case-insensitive lookup without normalising or copying the transport's header map. */
final class HttpHeaders {
  private HttpHeaders() {}

  static String first(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
        return entry.getValue().get(0);
      }
    }
    return null;
  }
}
