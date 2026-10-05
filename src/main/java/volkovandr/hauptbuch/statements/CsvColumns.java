package volkovandr.hauptbuch.statements;

import java.util.List;

/**
 * A profile's column map resolved to positions in one file's rows (statements.md §3.1): a column is
 * named by its header text, or by a 1-based index when the file has no header row. An unmapped
 * optional column has position {@code -1} and reads as empty.
 *
 * @param bookingDate the booking-date column
 * @param valueDate the value-date column
 * @param amount the signed amount column
 * @param debit the debit column
 * @param credit the credit column
 * @param currency the currency column
 * @param counterparty the counterparty column
 * @param description the description column
 * @param bankCategory the bank-category column
 * @param iban the IBAN column
 */
record CsvColumns(
    int bookingDate,
    int valueDate,
    int amount,
    int debit,
    int credit,
    int currency,
    int counterparty,
    int description,
    int bankCategory,
    int iban) {

  /**
   * Resolve {@code profile}'s columns against {@code headers}.
   *
   * @throws StatementFormatException when a mapped column is not in the file, or a required one is
   *     not mapped
   */
  static CsvColumns of(StatementProfile profile, List<String> headers) {
    boolean signed = !StatementProfile.SIGN_DEBIT_CREDIT.equals(profile.csvSignMode());
    boolean byName = Boolean.TRUE.equals(profile.csvHasHeader());
    return new CsvColumns(
        locate(byName, headers, profile.colBookingDate(), "booking date", true),
        locate(byName, headers, profile.colValueDate(), "value date", false),
        locate(byName, headers, profile.colAmount(), "amount", signed),
        locate(byName, headers, profile.colDebit(), "debit", !signed),
        locate(byName, headers, profile.colCredit(), "credit", !signed),
        locate(byName, headers, profile.colCurrency(), "currency", false),
        locate(byName, headers, profile.colCounterparty(), "counterparty", false),
        locate(byName, headers, profile.colDescription(), "description", false),
        locate(byName, headers, profile.colBankCategory(), "bank category", false),
        locate(byName, headers, profile.colIban(), "IBAN", false));
  }

  /** The trimmed field at {@code index}, or empty when the column is unmapped or the row short. */
  static String value(List<String> row, int index) {
    return index < 0 || index >= row.size() ? "" : row.get(index).strip();
  }

  private static int locate(
      boolean byName, List<String> headers, String name, String what, boolean required) {
    if (name == null || name.isBlank()) {
      if (required) {
        throw notSet(what);
      }
      return -1;
    }
    String wanted = name.strip();
    return byName ? headerIndex(headers, wanted, what) : numberedIndex(wanted, what);
  }

  private static int headerIndex(List<String> headers, String wanted, String what) {
    for (int i = 0; i < headers.size(); i++) {
      if (headers.get(i).strip().equalsIgnoreCase(wanted)) {
        return i;
      }
    }
    throw new StatementFormatException(
        "The %s column '%s' is not in the file's header. Found: %s."
            .formatted(what, wanted, String.join(", ", headers)));
  }

  private static int numberedIndex(String number, String what) {
    int index;
    try {
      index = Integer.parseInt(number);
    } catch (NumberFormatException e) {
      throw new StatementFormatException(
          "The %s column must be a 1-based number when the file has no header row.".formatted(what),
          e);
    }
    if (index < 1) {
      throw new StatementFormatException(
          "The %s column must be a 1-based number when the file has no header row."
              .formatted(what));
    }
    return index - 1;
  }

  /** The refusal for a required column the profile leaves blank. */
  static StatementFormatException notSet(String what) {
    return new StatementFormatException("The " + what + " column is not set.");
  }
}
