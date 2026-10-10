package volkovandr.hauptbuch.statements;

/**
 * A transport- or API-level failure of a statement parse: the call could not be made or returned no
 * usable body (network error, authentication failure, no API key). The statement lands in {@code
 * failed} with this message kept and no usage recorded.
 */
class StatementParseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** A failure with an underlying cause (the SDK or transport exception). */
  StatementParseException(String message, Throwable cause) {
    super(message, cause);
  }

  /** A failure with no distinct cause (e.g. no API key configured). */
  StatementParseException(String message) {
    super(message);
  }
}
