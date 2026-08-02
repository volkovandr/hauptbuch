package volkovandr.hauptbuch.receipts;

/**
 * A transport- or API-level failure of a receipt parse (stage 9e): the call could not be made or
 * returned no usable body (network error, authentication failure, an overloaded API). The analyse
 * worker turns this into a {@code failed} receipt with the reason in {@code parse_error} and no
 * usage/cost recorded — distinct from an undecodable-but-returned body, which is a seeding failure
 * that still keeps {@code parse_raw} and the usage. Retryable from the pane.
 */
public class ReceiptParseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** A parse failure with an underlying cause (e.g. the SDK/transport exception). */
  public ReceiptParseException(String message, Throwable cause) {
    super(message, cause);
  }

  /** A parse failure with no distinct cause (e.g. no API key configured). */
  public ReceiptParseException(String message) {
    super(message);
  }
}
