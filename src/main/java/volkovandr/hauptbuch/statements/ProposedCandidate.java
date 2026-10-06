package volkovandr.hauptbuch.statements;

/**
 * A candidate offered to a line, with the tier it falls in.
 *
 * @param candidate the ledger leg
 * @param tier why it is offered
 */
public record ProposedCandidate(StatementCandidate candidate, Tier tier) {

  /** The candidate tiers of statements.md §4.2 ({@code exact} also covers {@code ambiguous}). */
  public enum Tier {
    /** The statement's account, equal amount. */
    EXACT,
    /** The statement's account, different amount, similar payee. */
    AMOUNT_DIFFERS,
    /** Another own real account, equal amount, similar payee, not reconciled. */
    WRONG_ACCOUNT
  }
}
