package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;

/**
 * One end's balance check (statements.md §6.2): the bank's figure, the ledger's figure at that
 * date, the part of the difference the statement itself explains, and the remainder that nothing
 * explains. Only the unexplained remainder counts against green.
 *
 * @param bank the bank's balance, as typed or parsed
 * @param ledger the ledger's balance for the account at that end
 * @param explained the part of {@code bank - ledger} accounted for by postings matched to this
 *     statement but dated outside its period, and by boundary extras
 * @param unexplained {@code bank - ledger - explained}
 */
public record StatementBalanceCheck(
    BigDecimal bank, BigDecimal ledger, BigDecimal explained, BigDecimal unexplained) {

  /** Whether nothing is left unexplained. */
  public boolean agrees() {
    return unexplained.signum() == 0;
  }
}
