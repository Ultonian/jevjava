package net.codefinch.jev;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.codefinch.jev.internal.ErrorMessages;

/**
 * HTTP 422: the request body failed server-side validation. When the body has FastAPI's {@code
 * {"detail": [{"loc", "msg", "type"}]}} shape the entries are exposed as {@link #fieldErrors()};
 * for any other body shape that list is empty and the raw body is still available. Mirrors upstream
 * {@code TypeSafeUnprocessableEntityError}.
 */
public class JevUnprocessableEntityException extends JevApiException {
  private static final long serialVersionUID = 1L;

  private final ArrayList<FieldError> fieldErrors;

  /** Creates the exception; the message is extracted from the body. */
  public JevUnprocessableEntityException(
      Map<String, List<String>> headers, String rawBody, String endpoint) {
    super(422, headers, rawBody, endpoint, null);
    this.fieldErrors = new ArrayList<>(ErrorMessages.fieldErrors(rawBody));
  }

  /** Validation entries from a FastAPI-shaped body, or an empty list. Unmodifiable. */
  public List<FieldError> fieldErrors() {
    return Collections.unmodifiableList(fieldErrors);
  }

  /**
   * One validation entry.
   *
   * @param path dotted {@code loc} with the leading {@code body} segment removed; may be empty
   * @param message the {@code msg}
   * @param type the {@code type}, or empty string if absent
   */
  public record FieldError(String path, String message, String type)
      implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
  }
}
