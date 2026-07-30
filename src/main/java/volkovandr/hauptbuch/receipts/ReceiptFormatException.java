package volkovandr.hauptbuch.receipts;

/**
 * A captured upload was rejected before it reached disk — wrong image format (only JPEG and PNG are
 * accepted, validated by magic bytes not the client's content type) or over the size cap. Carries a
 * user-facing message the capture surfaces show verbatim.
 */
public class ReceiptFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Create the exception with a user-facing rejection message. */
  public ReceiptFormatException(String message) {
    super(message);
  }
}
