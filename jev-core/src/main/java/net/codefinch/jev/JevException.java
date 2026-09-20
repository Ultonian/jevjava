package net.codefinch.jev;

/**
 * Base of every exception this SDK throws. Unchecked. Mirrors upstream {@code TypeSafeError}.
 *
 * <p>Hierarchy: {@link JevApiException} for non-2xx responses and invalid success bodies, {@link
 * JevConnectionException} (and its subclasses) for transport failures, and a few Java-only types
 * for misuse of the answers API and for interruption.
 */
public class JevException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public JevException(String message) {
    super(message);
  }

  /** Creates the exception with a cause. */
  public JevException(String message, Throwable cause) {
    super(message, cause);
  }
}
