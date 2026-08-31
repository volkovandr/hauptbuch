package volkovandr.hauptbuch.importer;

/**
 * A QIF file, or one of its records, cannot be safely imported as read — an unsupported dialect
 * shape ({@code !Type:Invst}, import.md §4.5), an unrecognised header or field, or a value this
 * stage of the parser does not yet understand (e.g. a split, landing at a4). Carries a user-facing
 * message; the file is refused outright rather than staged with a guess (CLAUDE.md §0 — guessing at
 * parsing produces subtly wrong data).
 */
public class QifRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Create the exception with a user-facing rejection message. */
  public QifRejectedException(String message) {
    super(message);
  }

  /** Create the exception with a user-facing rejection message and an underlying cause. */
  public QifRejectedException(String message, Throwable cause) {
    super(message, cause);
  }
}
