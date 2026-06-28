package volkovandr.hauptbuch.ledger;

/**
 * Thrown when a submitted transaction violates a posting invariant <em>before</em> it reaches the
 * database (data-model §8): the legs do not sum to zero (in native for a single-currency
 * transaction, in base for a cross-currency one), a cross-currency leg is missing its frozen {@code
 * baseAmount}, or a leg posts to a non-leaf account.
 *
 * <p>This is the engine rejecting bad input at the service boundary — the unit tier's primary
 * subject (plan §15). It is distinct from {@link IllegalStateException}, which the engine uses for
 * a precondition on the book itself (base currency unset).
 */
public class UnbalancedTransactionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Create the exception.
   *
   * @param message which invariant the submitted transaction violated
   */
  public UnbalancedTransactionException(String message) {
    super(message);
  }
}
