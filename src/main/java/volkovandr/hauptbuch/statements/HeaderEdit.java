package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The statement header as the operator typed it (statements.md §3.4): ISO dates and lenient
 * German/English balances; blank means empty, and no balances means a turnover-only check.
 *
 * @param periodStart the first day the statement covers
 * @param periodEnd the last day the statement covers
 * @param openingBalance the bank's opening balance
 * @param closingBalance the bank's closing balance
 */
public record HeaderEdit(
    String periodStart, String periodEnd, String openingBalance, String closingBalance) {

  /**
   * Read the typed values.
   *
   * @throws StatementFormatException when a value cannot be read or the period is reversed
   */
  Header read() {
    LocalDate start = TypedValues.typedDate(periodStart, "period start", "");
    LocalDate end = TypedValues.typedDate(periodEnd, "period end", "");
    if (start != null && end != null && end.isBefore(start)) {
      throw new StatementFormatException("The period ends before it starts.");
    }
    return new Header(
        start,
        end,
        TypedValues.typedAmount(openingBalance, "opening balance", ""),
        TypedValues.typedAmount(closingBalance, "closing balance", ""));
  }

  /** The header once read. */
  record Header(
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal openingBalance,
      BigDecimal closingBalance) {}
}
