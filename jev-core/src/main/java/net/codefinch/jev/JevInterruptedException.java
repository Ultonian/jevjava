package net.codefinch.jev;

/**
 * The calling thread of a synchronous call was interrupted. The interrupt flag has been re-asserted
 * before this is thrown. Terminal: never retried. Java-only.
 */
public class JevInterruptedException extends JevException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public JevInterruptedException(String message, Throwable cause) {
    super(message, cause);
  }
}
