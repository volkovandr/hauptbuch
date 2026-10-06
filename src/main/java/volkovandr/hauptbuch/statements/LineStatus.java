package volkovandr.hauptbuch.statements;

/** Where a statement line stands against the ledger (statements.md §4.2, §6.3). */
public enum LineStatus {
  /** The line has a confirmed match. */
  MATCHED("matched"),
  /** One unambiguous candidate on the statement's account with an equal amount. */
  EXACT("exact"),
  /** Several equal-amount candidates on the statement's account; the operator picks. */
  AMBIGUOUS("ambiguous"),
  /** The one exact candidate is already matched on another statement; the operator decides. */
  OVERLAP("already on another statement"),
  /** Candidates on the statement's account with a different amount and a similar payee. */
  AMOUNT_DIFFERS("amount differs"),
  /** An equal-amount candidate booked to another own account. */
  WRONG_ACCOUNT("wrong account"),
  /** No candidate at all; the transaction is to be created. */
  MISSING("missing"),
  /** The line could not be read, or is in a foreign currency; it is never matched. */
  PROBLEM("problem");

  private final String text;

  LineStatus(String label) {
    this.text = label;
  }

  /** The status as the operator reads it. */
  public String label() {
    return text;
  }
}
