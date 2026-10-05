package volkovandr.hauptbuch.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads a bank's CSV through a {@link StatementProfile} (statements.md §3.1, §3.3). A problem with
 * the file as a whole — a column the header does not have, a dialect that cannot be applied — is a
 * {@link StatementFormatException}; a problem with one row is kept on that row's line and the rest
 * of the file still reads.
 */
@Component
public class StatementCsvParser {

  /**
   * Read {@code bytes} through {@code profile}.
   *
   * @param accountCurrency the statement account's currency; a row in another currency becomes a
   *     problem line, never a converted one. Null (the account not yet chosen) skips the check
   * @param maxRows how many data rows to read, or {@code Integer.MAX_VALUE} for all (the profile
   *     preview reads a handful)
   * @throws StatementFormatException when the profile cannot be applied to this file
   */
  public CsvStatement parse(
      StatementProfile profile, byte[] bytes, String accountCurrency, int maxRows) {
    CsvDialect dialect = CsvDialect.of(profile);
    List<List<String>> rows =
        CsvReader.read(new String(bytes, dialect.charset()), dialect.delimiter(), dialect.quote());
    int skip = profile.csvSkipRows() == null ? 0 : Math.max(0, profile.csvSkipRows());
    boolean hasHeader = Boolean.TRUE.equals(profile.csvHasHeader());
    int first = skip + (hasHeader ? 1 : 0);
    if (rows.size() <= first) {
      return new CsvStatement(List.of(), List.of(), Set.of());
    }
    List<String> headers = hasHeader ? rows.get(skip) : List.of();
    CsvColumns columns = CsvColumns.of(profile, headers);
    List<StatementLine> lines = new ArrayList<>();
    Set<String> ibans = new LinkedHashSet<>();
    for (int i = first; i < rows.size() && lines.size() < maxRows; i++) {
      List<String> row = rows.get(i);
      String iban = CsvColumns.value(row, columns.iban());
      if (!iban.isEmpty()) {
        ibans.add(iban);
      }
      lines.add(line(profile, dialect, columns, row, lines.size(), accountCurrency));
    }
    return new CsvStatement(headers, lines, ibans);
  }

  /**
   * Check that {@code profile} can be applied to some file: a valid dialect and every column its
   * sign mode needs. A header lookup against a real file happens later, in {@link #parse}.
   *
   * @throws StatementFormatException naming the first setting that is wrong
   */
  public void validate(StatementProfile profile) {
    CsvDialect.of(profile);
    boolean signed = StatementProfile.SIGN_SIGNED.equals(profile.csvSignMode());
    if (!signed && !StatementProfile.SIGN_DEBIT_CREDIT.equals(profile.csvSignMode())) {
      throw new StatementFormatException("Choose a sign mode.");
    }
    require(profile.colBookingDate(), "booking date");
    if (signed) {
      require(profile.colAmount(), "amount");
    } else {
      require(profile.colDebit(), "debit");
      require(profile.colCredit(), "credit");
    }
  }

  private static void require(String column, String what) {
    if (column == null || column.isBlank()) {
      throw CsvColumns.notSet(what);
    }
  }

  private static StatementLine line(
      StatementProfile profile,
      CsvDialect dialect,
      CsvColumns columns,
      List<String> row,
      int sortOrder,
      String accountCurrency) {
    LocalDate booking = null;
    LocalDate value = null;
    BigDecimal amount = null;
    String problem;
    try {
      booking = bookingDate(dialect, columns, row);
      value =
          TypedValues.date(
              dialect.dateFormat(), CsvColumns.value(row, columns.valueDate()), "value date");
      amount = amount(profile, dialect, columns, row);
      problem = currencyProblem(CsvColumns.value(row, columns.currency()), accountCurrency);
    } catch (RowProblem e) {
      problem = e.getMessage();
    }
    return new StatementLine(
        null,
        sortOrder,
        booking,
        value,
        amount,
        blankToNull(CsvColumns.value(row, columns.counterparty())),
        blankToNull(CsvColumns.value(row, columns.description())),
        blankToNull(CsvColumns.value(row, columns.bankCategory())),
        String.join(String.valueOf(dialect.delimiter()), row),
        problem);
  }

  private static LocalDate bookingDate(CsvDialect dialect, CsvColumns columns, List<String> row) {
    LocalDate booking =
        TypedValues.date(
            dialect.dateFormat(), CsvColumns.value(row, columns.bookingDate()), "booking date");
    if (booking == null) {
      throw new RowProblem("No booking date");
    }
    return booking;
  }

  private static String currencyProblem(String currency, String accountCurrency) {
    if (accountCurrency == null
        || currency.isEmpty()
        || currency.equalsIgnoreCase(accountCurrency)) {
      return null;
    }
    return "Currency " + currency + ", but the account is in " + accountCurrency;
  }

  private static BigDecimal amount(
      StatementProfile profile, CsvDialect dialect, CsvColumns columns, List<String> row) {
    if (StatementProfile.SIGN_DEBIT_CREDIT.equals(profile.csvSignMode())) {
      BigDecimal debit =
          TypedValues.number(dialect.decimal(), CsvColumns.value(row, columns.debit()), "debit");
      BigDecimal credit =
          TypedValues.number(dialect.decimal(), CsvColumns.value(row, columns.credit()), "credit");
      if (debit == null && credit == null) {
        throw new RowProblem("Neither a debit nor a credit amount");
      }
      BigDecimal in = credit == null ? BigDecimal.ZERO : credit.abs();
      BigDecimal out = debit == null ? BigDecimal.ZERO : debit.abs();
      return in.subtract(out);
    }
    BigDecimal amount =
        TypedValues.number(dialect.decimal(), CsvColumns.value(row, columns.amount()), "amount");
    if (amount == null) {
      throw new RowProblem("No amount");
    }
    return amount;
  }

  private static String blankToNull(String text) {
    return text.isEmpty() ? null : text;
  }
}
