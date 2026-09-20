package net.codefinch.jev;

/**
 * One attempt exceeded its per-attempt timeout (headers or body delivery). Retried by default.
 * Mirrors upstream {@code TypeSafeAPITimeoutError}, which is likewise a connection error.
 */
public class JevTimeoutException extends JevConnectionException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public JevTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
