package volkovandr.hauptbuch.statements;

/**
 * A statement file or profile the operator has to fix: an unreadable header, a column the file does
 * not have, a bad dialect setting. The message is written for the screen.
 */
public class StatementFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** An exception whose {@code message} is shown to the operator as written. */
  public StatementFormatException(String message) {
    super(message);
  }

  /** As above, keeping the {@code cause} that made the input unreadable. */
  public StatementFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
