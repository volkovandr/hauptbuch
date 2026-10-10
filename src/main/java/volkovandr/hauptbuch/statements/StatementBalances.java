package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import volkovandr.hauptbuch.statements.ExtraReview.Boundary;

/**
 * Both balance checks of a statement (statements.md §6.2). A check is absent when its bank balance
 * is, so a CSV statement without balances shows none and is judged on turnover only.
 *
 * @param opening the opening check, or null
 * @param closing the closing check, or null
 */
public record StatementBalances(StatementBalanceCheck opening, StatementBalanceCheck closing) {

  /** Whether every check that exists agrees (vacuously true with none). */
  public boolean agree() {
    return (opening == null || opening.agrees()) && (closing == null || closing.agrees());
  }

  /**
   * Work both checks out. The ledger dates a purchase when it was made, the bank when it booked
   * it, so the difference at each end is explained by this statement's matched postings that sit
   * outside the period, and by the boundary extras — legs the ledger dates inside the period that
   * the bank books on a neighbouring statement.
   *
   * @param statement the statement, for its period and bank balances
   * @param review the live review, for the matches and the extras
   * @param ledgerOpening the account's ledger balance the day before the period starts
   * @param ledgerClosing the account's ledger balance at the end of the period
   */
  static StatementBalances of(
      Statement statement,
      StatementReview review,
      BigDecimal ledgerOpening,
      BigDecimal ledgerClosing) {
    List<StatementMatch> matches =
        review.lines().stream().map(LineReview::match).filter(m -> m != null).toList();
    BigDecimal beforePeriod =
        sum(matches.stream().filter(m -> m.transactionDate().isBefore(statement.periodStart())));
    BigDecimal afterPeriod =
        sum(matches.stream().filter(m -> m.transactionDate().isAfter(statement.periodEnd())));
    BigDecimal previousExtras = boundary(review, Boundary.PREVIOUS);
    BigDecimal nextExtras = boundary(review, Boundary.NEXT);
    // Opening: the ledger counts what the bank books inside the period (matched, dated before it),
    // and misses what the bank booked before it (probably on the previous statement).
    BigDecimal explainedOpening = previousExtras.subtract(beforePeriod);
    // Closing: the bank has what the ledger dates after the period, and lacks what the ledger
    // dates inside it but the bank books later (probably on the next statement).
    BigDecimal explainedClosing = afterPeriod.subtract(nextExtras);
    return new StatementBalances(
        check(statement.openingBalance(), ledgerOpening, explainedOpening),
        check(statement.closingBalance(), ledgerClosing, explainedClosing));
  }

  private static StatementBalanceCheck check(
      BigDecimal bank, BigDecimal ledger, BigDecimal explained) {
    if (bank == null) {
      return null;
    }
    return new StatementBalanceCheck(
        bank, ledger, explained, bank.subtract(ledger).subtract(explained));
  }

  private static BigDecimal boundary(StatementReview review, Boundary which) {
    return review.extras().stream()
        .filter(e -> e.boundary() == which)
        .map(e -> e.extra().amount())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal sum(Stream<StatementMatch> matches) {
    return matches.map(StatementMatch::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** The day before a period starts: the date the opening ledger balance is read at. */
  static LocalDate dayBefore(LocalDate periodStart) {
    return periodStart.minusDays(1);
  }
}
