package net.codefinch.jev.exception;

/**
 * The whole operation — all attempts, body delivery and backoff sleeps — exceeded its deadline. The
 * cause, when present, is the last attempt's failure. Terminal: never retried. Java-only; the
 * Python SDK's retry budget only stops scheduling further retries and the JavaScript SDK has none.
 *
 * @since 0.1.0
 */
public class JevDeadlineExceededException extends JevTimeoutException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public JevDeadlineExceededException(String message, Throwable cause) {
    super(message, cause);
  }
}
