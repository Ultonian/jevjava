package net.codefinch.jev;

/**
 * A typed accessor on {@link Answers} was called for an id whose answer is a different primitive.
 * Java-only.
 */
public class JevAnswerTypeException extends JevException {
  private static final long serialVersionUID = 1L;

  private final String id;
  private final String expected;
  private final String actual;

  /** Creates the exception. */
  public JevAnswerTypeException(String id, Class<?> expected, Class<?> actual) {
    super(
        "answer '"
            + id
            + "' is a "
            + actual.getSimpleName()
            + ", not a "
            + expected.getSimpleName());
    this.id = id;
    this.expected = expected.getSimpleName();
    this.actual = actual.getSimpleName();
  }

  /** The requested id. */
  public String id() {
    return id;
  }

  /** Simple name of the requested answer type. */
  public String expected() {
    return expected;
  }

  /** Simple name of the actual answer type. */
  public String actual() {
    return actual;
  }
}
