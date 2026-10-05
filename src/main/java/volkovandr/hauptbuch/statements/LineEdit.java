package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the line grid as the operator typed it (statements.md §6.1). Dates are ISO text from
 * the date inputs and the amount is the lenient German/English text {@code MoneyFormat} parses;
 * blank means empty.
 *
 * @param statementLineId the line being edited
 * @param bookingDate the booking date
 * @param valueDate the value date
 * @param amount the signed amount in the account's currency
 * @param counterparty the counterparty text
 * @param description the description text
 * @param bankCategory the bank's category label
 */
public record LineEdit(
    long statementLineId,
    String bookingDate,
    String valueDate,
    String amount,
    String counterparty,
    String description,
    String bankCategory) {

  private static final String PROBLEM_INCOMPLETE = "A line needs a booking date and an amount";
  private static final String PROBLEM_CURRENCY_PREFIX = "Currency ";

  /**
   * {@code old} with this edit applied. A line left without a date or an amount is flagged as a
   * problem; a line whose problem was the foreign currency keeps it; any other problem clears.
   *
   * @throws StatementFormatException naming the line and the entry that cannot be read
   */
  StatementLine applyTo(StatementLine old) {
    String prefix = "Line " + (old.sortOrder() + 1) + ": ";
    LocalDate booking = TypedValues.typedDate(bookingDate, "booking date", prefix);
    LocalDate value = TypedValues.typedDate(valueDate, "value date", prefix);
    BigDecimal money = TypedValues.typedAmount(amount, "amount", prefix);
    return new StatementLine(
        old.statementLineId(),
        old.sortOrder(),
        booking,
        value,
        money,
        blankToNull(counterparty),
        blankToNull(description),
        blankToNull(bankCategory),
        old.rawText(),
        problem(old, booking, money));
  }

  private static String problem(StatementLine old, LocalDate booking, BigDecimal money) {
    if (booking == null || money == null) {
      return PROBLEM_INCOMPLETE;
    }
    if (old.problem() != null && old.problem().startsWith(PROBLEM_CURRENCY_PREFIX)) {
      return old.problem();
    }
    return null;
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }
}
