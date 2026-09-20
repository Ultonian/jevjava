package net.codefinch.jev;

/**
 * A typed accessor on {@link Answers} was called for an id the response does not contain. This
 * happens for an id that was never asked, or when the server returned an answer kind this SDK does
 * not recognise (dropped, but still visible in the raw body). Java-only.
 */
public class JevMissingAnswerException extends JevException {
  private static final long serialVersionUID = 1L;

  private final String id;

  /** Creates the exception for a question id. */
  public JevMissingAnswerException(String id) {
    super("no answer for question id '" + id + "'");
    this.id = id;
  }

  /** The requested id. */
  public String id() {
    return id;
  }
}
