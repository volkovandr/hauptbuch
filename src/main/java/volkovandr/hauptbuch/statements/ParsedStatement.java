package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A decoded parser body: the header (period and balances, each possibly absent) and the lines in
 * the order the statement printed them.
 *
 * @param periodStart the first day the statement covers, or null
 * @param periodEnd the last day it covers, or null
 * @param openingBalance the opening balance, or null
 * @param closingBalance the closing balance, or null
 * @param lines the bookings
 */
record ParsedStatement(
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal openingBalance,
    BigDecimal closingBalance,
    List<ParsedLine> lines) {

  /**
   * One decoded booking: the shared line shape, plus the foreign charge the bank printed.
   *
   * @param line the line, {@code problem} set when its date or amount could not be read
   * @param originalAmount the foreign amount, or null
   * @param originalCurrency the foreign ISO code, or null
   * @param originalRate the exchange rate printed, or null
   */
  record ParsedLine(
      StatementLine line,
      BigDecimal originalAmount,
      String originalCurrency,
      BigDecimal originalRate) {}
}
