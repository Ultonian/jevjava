package net.codefinch.jev;

import java.util.List;
import java.util.Map;

/**
 * A 2xx response whose body does not match the API schema: not JSON, a required field missing or of
 * the wrong type, or an answer of a known type that is malformed. Carries the dotted path of the
 * offending field. Mirrors upstream {@code TypeSafeAPIResponseValidationError}.
 */
public class JevResponseValidationException extends JevApiException {
  private static final long serialVersionUID = 1L;

  private final String fieldPath;

  /** Creates the exception for a field path. */
  public JevResponseValidationException(
      int status,
      Map<String, List<String>> headers,
      String rawBody,
      String endpoint,
      String fieldPath) {
    super(status, headers, rawBody, endpoint, "Invalid response data at '" + fieldPath + "'.");
    this.fieldPath = fieldPath;
  }

  /** Dotted path of the invalid field, e.g. {@code answers.q.confidence}. */
  public String fieldPath() {
    return fieldPath;
  }
}
