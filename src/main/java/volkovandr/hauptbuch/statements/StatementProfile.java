package volkovandr.hauptbuch.statements;

import java.time.OffsetDateTime;

/**
 * How to read one statement source (data-model §15): the matching date window and, for a CSV, the
 * dialect, the sign mode and the column map. A column is named by its header text, or by a 1-based
 * index when {@code csvHasHeader} is false (statements.md §3.1). The same record is the form the
 * profile screen binds and the row the repository reads; an unsaved profile has a null id.
 *
 * @param statementProfileId the id, or null before the first save
 * @param name the operator's name for the source
 * @param format {@code csv}, or {@code pdf} (a PDF profile holds only the name, the window and the
 *     AI note)
 * @param windowDaysBefore how many days before the booking date a ledger posting may be dated
 * @param windowDaysAfter how many days after the booking date a ledger posting may be dated
 * @param aiNote PDF only: per-bank guidance for the parser
 * @param csvDelimiter the field delimiter, one character
 * @param csvQuote the quote character, one character
 * @param csvEncoding a charset name such as {@code UTF-8} or {@code ISO-8859-1}
 * @param csvSkipRows rows above the column-name row (or above the data when there is no header)
 * @param csvHasHeader whether a column-name row follows the skipped rows
 * @param csvDecimalSeparator {@code ,} or {@code .}
 * @param csvDateFormat a {@code java.time} pattern such as {@code dd.MM.yyyy}
 * @param csvSignMode {@code signed} (one amount column) or {@code debit_credit} (two)
 * @param colBookingDate the booking-date column
 * @param colValueDate the value-date column, optional
 * @param colAmount the signed amount column ({@code signed} mode)
 * @param colDebit the debit column ({@code debit_credit} mode)
 * @param colCredit the credit column ({@code debit_credit} mode)
 * @param colCurrency the currency column, optional; a row in another currency becomes a problem
 * @param colCounterparty the counterparty column, optional
 * @param colDescription the description column, optional
 * @param colBankCategory the bank's own category column, optional
 * @param colIban the owner's IBAN column, optional, for the account proposal
 * @param deletedAt when the profile was soft-deleted, or null
 */
public record StatementProfile(
    Long statementProfileId,
    String name,
    String format,
    int windowDaysBefore,
    int windowDaysAfter,
    String aiNote,
    String csvDelimiter,
    String csvQuote,
    String csvEncoding,
    Integer csvSkipRows,
    Boolean csvHasHeader,
    String csvDecimalSeparator,
    String csvDateFormat,
    String csvSignMode,
    String colBookingDate,
    String colValueDate,
    String colAmount,
    String colDebit,
    String colCredit,
    String colCurrency,
    String colCounterparty,
    String colDescription,
    String colBankCategory,
    String colIban,
    OffsetDateTime deletedAt) {

  /** The {@code format} of a CSV profile. */
  public static final String FORMAT_CSV = "csv";

  /** The {@code format} of a PDF profile. */
  public static final String FORMAT_PDF = "pdf";

  /** The {@code csvSignMode} with one signed amount column. */
  public static final String SIGN_SIGNED = "signed";

  /** The {@code csvSignMode} with separate debit and credit columns. */
  public static final String SIGN_DEBIT_CREDIT = "debit_credit";

  /** Whether this profile reads a PDF, which the AI parses, rather than a CSV. */
  public boolean isPdf() {
    return FORMAT_PDF.equals(format);
  }

  /** A new PDF profile with the defaults the form starts from. */
  public static StatementProfile blankPdf() {
    return new StatementProfile(
        null,
        "",
        FORMAT_PDF,
        10,
        3,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** A new CSV profile with the defaults the form starts from. */
  public static StatementProfile blankCsv() {
    return new StatementProfile(
        null,
        "",
        FORMAT_CSV,
        10,
        3,
        null,
        ";",
        "\"",
        "UTF-8",
        0,
        true,
        ",",
        "dd.MM.yyyy",
        SIGN_SIGNED,
        "",
        null,
        "",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
