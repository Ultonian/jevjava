package net.codefinch.jev.exception;

/**
 * The request could not reach the server or the response could not be read. Retried by default.
 * Mirrors upstream {@code TypeSafeAPIConnectionError}.
 *
 * @since 0.1.0
 */
public class JevConnectionException extends JevException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public JevConnectionException(String message, Throwable cause) {
    super(message, cause);
  }
}
