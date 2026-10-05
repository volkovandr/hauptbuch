package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tier: {@link StatementCsvParser} reads a bank CSV through a profile (statements.md §3.1,
 * §3.3) — columns by header name or 1-based index, both sign modes, both decimal conventions, the
 * header and skipped rows — and keeps a row it cannot read, or that is in a foreign currency, as a
 * line with a problem rather than failing the file.
 */
class StatementCsvParserTest {

  private static final String HEADER =
      "Booking;Value;Amount;Currency;Counterparty;Text;Category;IBAN\n";

  private final StatementCsvParser parser = new StatementCsvParser();

  private static StatementProfile signed() {
    return new StatementProfile(
        null,
        "BankAaa CSV",
        "csv",
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
        "signed",
        "Booking",
        "Value",
        "Amount",
        null,
        null,
        "Currency",
        "Counterparty",
        "Text",
        "Category",
        "IBAN",
        null);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void readsSignedFileByHeaderName() {
    String csv =
        HEADER
            + "02.05.2026;03.05.2026;-12,50;EUR;ShopAaa;Card payment;Groceries;XX00 1111\n"
            + "04.05.2026;04.05.2026;1.234,56;EUR;Employer;Salary;Income;XX00 1111\n";

    CsvStatement result = parser.parse(signed(), bytes(csv), "EUR", Integer.MAX_VALUE);

    assertThat(result.headers()).startsWith("Booking", "Value");
    assertThat(result.lines()).hasSize(2);
    StatementLine first = result.lines().get(0);
    assertThat(first.bookingDate()).isEqualTo(LocalDate.of(2026, 5, 2));
    assertThat(first.valueDate()).isEqualTo(LocalDate.of(2026, 5, 3));
    assertThat(first.amount()).isEqualByComparingTo("-12.50");
    assertThat(first.counterparty()).isEqualTo("ShopAaa");
    assertThat(first.description()).isEqualTo("Card payment");
    assertThat(first.bankCategory()).isEqualTo("Groceries");
    assertThat(first.problem()).isNull();
    assertThat(result.lines().get(1).amount()).isEqualByComparingTo("1234.56");
    assertThat(result.ibans()).containsExactly("XX00 1111");
  }

  @Test
  void findsHeaderNamesCaseInsensitivelyAndSurvivesReorderedColumns() {
    String csv = "iban;text;amount;booking\nXX00;Coffee;-3,50;05.05.2026\n";
    StatementProfile profile =
        new StatementProfile(
            null,
            "p",
            "csv",
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
            "signed",
            "BOOKING",
            null,
            "Amount",
            null,
            null,
            null,
            null,
            "Text",
            null,
            "IBAN",
            null);

    StatementLine line = parser.parse(profile, bytes(csv), "EUR", 10).lines().get(0);

    assertThat(line.bookingDate()).isEqualTo(LocalDate.of(2026, 5, 5));
    assertThat(line.amount()).isEqualByComparingTo("-3.50");
    assertThat(line.description()).isEqualTo("Coffee");
  }

  @Test
  void readsFileWithoutHeaderByOneBasedIndexAfterSkippedRows() {
    String csv = "Exported 2026-06-01\n05.05.2026;-3.50;Coffee\n";
    StatementProfile profile =
        new StatementProfile(
            null,
            "p",
            "csv",
            10,
            3,
            null,
            ";",
            "\"",
            "UTF-8",
            1,
            false,
            ".",
            "dd.MM.yyyy",
            "signed",
            "1",
            null,
            "2",
            null,
            null,
            null,
            null,
            "3",
            null,
            null,
            null);

    CsvStatement result = parser.parse(profile, bytes(csv), "EUR", 10);

    assertThat(result.headers()).isEmpty();
    assertThat(result.lines()).hasSize(1);
    assertThat(result.lines().get(0).amount()).isEqualByComparingTo("-3.50");
    assertThat(result.lines().get(0).description()).isEqualTo("Coffee");
  }

  @Test
  void combinesDebitAndCreditColumnsIntoOneSignedAmount() {
    String csv = "Date;Debit;Credit\n01.05.2026;12,00;\n02.05.2026;;40,00\n03.05.2026;-5,00;\n";
    StatementProfile profile =
        new StatementProfile(
            null,
            "p",
            "csv",
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
            "debit_credit",
            "Date",
            null,
            null,
            "Debit",
            "Credit",
            null,
            null,
            null,
            null,
            null,
            null);

    List<StatementLine> lines = parser.parse(profile, bytes(csv), "EUR", 10).lines();

    assertThat(lines)
        .extracting(StatementLine::amount)
        .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
        .containsExactly(
            new BigDecimal("-12.00"), new BigDecimal("40.00"), new BigDecimal("-5.00"));
  }

  @Test
  void keepsForeignCurrencyRowWithProblem() {
    String csv = HEADER + "02.05.2026;;-10,00;USD;ShopAaa;Card;;\n";

    StatementLine line = parser.parse(signed(), bytes(csv), "EUR", 10).lines().get(0);

    assertThat(line.problem()).isEqualTo("Currency USD, but the account is in EUR");
    assertThat(line.amount()).isEqualByComparingTo("-10.00");
  }

  @Test
  void skipsTheCurrencyCheckBeforeTheAccountIsChosen() {
    String csv = HEADER + "02.05.2026;;-10,00;USD;ShopAaa;Card;;\n";

    assertThat(parser.parse(signed(), bytes(csv), null, 10).lines().get(0).problem()).isNull();
  }

  @Test
  void keepsUnreadableRowWithProblemAndReadsTheRest() {
    String csv =
        HEADER
            + "yesterday;;-10,00;EUR;A;B;;\n"
            + "02.05.2026;;oops;EUR;A;B;;\n"
            + "03.05.2026;;-1,00;EUR;A;B;;\n";

    List<StatementLine> lines = parser.parse(signed(), bytes(csv), "EUR", 10).lines();

    assertThat(lines).hasSize(3);
    assertThat(lines.get(0).problem()).isEqualTo("Unreadable booking date 'yesterday'");
    assertThat(lines.get(1).problem()).isEqualTo("Unreadable amount 'oops'");
    assertThat(lines.get(2).problem()).isNull();
    assertThat(lines.get(0).rawText()).isEqualTo("yesterday;;-10,00;EUR;A;B;;");
  }

  @Test
  void stopsAtTheRequestedNumberOfRows() {
    String csv = HEADER + "02.05.2026;;-1,00;EUR;A;B;;\n".repeat(5);

    assertThat(parser.parse(signed(), bytes(csv), "EUR", 2).lines()).hasSize(2);
  }

  @Test
  void fileWithOnlyHeaderHasNoLines() {
    assertThat(parser.parse(signed(), bytes(HEADER), "EUR", 10).lines()).isEmpty();
  }

  @Test
  void refusesColumnTheHeaderDoesNotHave() {
    StatementProfile profile =
        new StatementProfile(
            null,
            "p",
            "csv",
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
            "signed",
            "Buchungstag",
            null,
            "Amount",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> parser.parse(profile, bytes(HEADER + "x"), "EUR", 10))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("'Buchungstag' is not in the file's header")
        .hasMessageContaining("Booking");
  }

  @Test
  void decodesTheConfiguredEncoding() {
    String csv = HEADER + "02.05.2026;;-1,00;EUR;Müller;Café;;\n";
    StatementProfile profile = withEncoding("ISO-8859-1");

    StatementLine line =
        parser.parse(profile, csv.getBytes(StandardCharsets.ISO_8859_1), "EUR", 10).lines().get(0);

    assertThat(line.counterparty()).isEqualTo("Müller");
  }

  @Test
  void validateNamesTheFirstWrongSetting() {
    assertThatThrownBy(() -> parser.validate(withEncoding("NOPE-9")))
        .hasMessage("Unknown encoding 'NOPE-9'.");
    assertThatThrownBy(() -> parser.validate(withDelimiter("ab")))
        .hasMessage("The delimiter must be exactly one character.");
    assertThatThrownBy(() -> parser.validate(withDateFormat("dd.MM.'yyyy")))
        .hasMessage("The date format 'dd.MM.'yyyy' is not valid.");
    assertThatThrownBy(() -> parser.validate(withBookingDate(" ")))
        .hasMessage("The booking date column is not set.");
  }

  @Test
  void validateAcceptsWellFormedProfileAndTabDelimiter() {
    assertThatCode(() -> parser.validate(signed())).doesNotThrowAnyException();
    assertThatCode(() -> parser.validate(withDelimiter("\\t"))).doesNotThrowAnyException();
  }

  private static StatementProfile withEncoding(String encoding) {
    StatementProfile p = signed();
    return new StatementProfile(
        null,
        p.name(),
        p.format(),
        10,
        3,
        null,
        p.csvDelimiter(),
        p.csvQuote(),
        encoding,
        p.csvSkipRows(),
        p.csvHasHeader(),
        p.csvDecimalSeparator(),
        p.csvDateFormat(),
        p.csvSignMode(),
        p.colBookingDate(),
        p.colValueDate(),
        p.colAmount(),
        null,
        null,
        p.colCurrency(),
        p.colCounterparty(),
        p.colDescription(),
        p.colBankCategory(),
        p.colIban(),
        null);
  }

  private static StatementProfile withDelimiter(String delimiter) {
    StatementProfile p = signed();
    return new StatementProfile(
        null,
        p.name(),
        p.format(),
        10,
        3,
        null,
        delimiter,
        p.csvQuote(),
        p.csvEncoding(),
        p.csvSkipRows(),
        p.csvHasHeader(),
        p.csvDecimalSeparator(),
        p.csvDateFormat(),
        p.csvSignMode(),
        p.colBookingDate(),
        p.colValueDate(),
        p.colAmount(),
        null,
        null,
        p.colCurrency(),
        p.colCounterparty(),
        p.colDescription(),
        p.colBankCategory(),
        p.colIban(),
        null);
  }

  private static StatementProfile withDateFormat(String pattern) {
    StatementProfile p = signed();
    return new StatementProfile(
        null,
        p.name(),
        p.format(),
        10,
        3,
        null,
        p.csvDelimiter(),
        p.csvQuote(),
        p.csvEncoding(),
        p.csvSkipRows(),
        p.csvHasHeader(),
        p.csvDecimalSeparator(),
        pattern,
        p.csvSignMode(),
        p.colBookingDate(),
        p.colValueDate(),
        p.colAmount(),
        null,
        null,
        p.colCurrency(),
        p.colCounterparty(),
        p.colDescription(),
        p.colBankCategory(),
        p.colIban(),
        null);
  }

  private static StatementProfile withBookingDate(String column) {
    StatementProfile p = signed();
    return new StatementProfile(
        null,
        p.name(),
        p.format(),
        10,
        3,
        null,
        p.csvDelimiter(),
        p.csvQuote(),
        p.csvEncoding(),
        p.csvSkipRows(),
        p.csvHasHeader(),
        p.csvDecimalSeparator(),
        p.csvDateFormat(),
        p.csvSignMode(),
        column,
        p.colValueDate(),
        p.colAmount(),
        null,
        null,
        p.colCurrency(),
        p.colCounterparty(),
        p.colDescription(),
        p.colBankCategory(),
        p.colIban(),
        null);
  }
}
