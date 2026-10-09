package volkovandr.hauptbuch.statements;

/**
 * The statement page's dock cannot change this transaction (a split, a cross-currency extra, the
 * far end of a transfer) but the register can: the page offers a link to it.
 */
public class RegisterOnlyException extends StatementFormatException {

  private static final long serialVersionUID = 1L;

  private final long editableTransactionId;

  /** Why the dock refuses {@code transactionId}; the message is shown as written. */
  public RegisterOnlyException(String message, long transactionId) {
    super(message);
    this.editableTransactionId = transactionId;
  }

  /** As above, keeping the {@code cause} that made the dock refuse it. */
  public RegisterOnlyException(String message, long transactionId, Throwable cause) {
    super(message, cause);
    this.editableTransactionId = transactionId;
  }

  /** The transaction the register can edit. */
  public long transactionId() {
    return editableTransactionId;
  }
}
