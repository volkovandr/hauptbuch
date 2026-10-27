package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.statements.ParsedStatement.ParsedLine;

/**
 * Unit tier: the parse rule engine (statements.md §3.2a) — statement text and a profile's rules in,
 * TOON out. Each test decodes the TOON with the real {@link ToonStatementDecoder}, so the engine is
 * proven against the shape the rest of the PDF flow reads.
 */
class ParseRuleEngineTest {

  /** A bank B-style statement: letterhead, a page break, a footer; placeholder names only. */
  private static final String STATEMENT =
      """
      Kontoauszug vom 31.08.2026
      Auszug-Nr. 8 Seite-Nr. 1
      BankBbb, Musterstraße 1, 99999 Musterstadt
      Alter Kontostand vom 31.07.2026 42.823,20
      Angaben zu den Umsätzen Valuta
      Buchungsdatum: 03.08.2026
      SHOPAAA AUTOROUTE/SOMEWHERE 03.08 6,80-
      2026-07-29T15:24:51 KFN 0 VJ 2912
      Kartenzahlung
      SHOPBBB GMBH//MUSTERSTADT/D 03.08 1.208,79-
      Kartenzahlung
      Folgeseite 2
      Kontoauszug vom 31.08.2026 Seite-Nr. 2
      Angaben zu den Umsätzen Valuta
      Buchungsdatum: 04.08.2026
      EMPLOYER AAA GMBH 04.08 3.100,00
      Gehalt August
      Guthaben sind als Einlagen nach Maßgabe des Gesetzes entschädigungsfähig.
      Neuer Kontostand vom 31.08.2026 44.714,41
      """;

  private static final ParseRuleSettings SETTINGS =
      new ParseRuleSettings(
          ".",
          ",",
          ParseRuleSettings.MINUS_TRAILING,
          "dd.MM.yyyy",
          ParseRuleSettings.CONTINUATION_PREVIOUS,
          null,
          true);

  private static final ParseRule BOOKING_DATE =
      new ParseRule(ParseRule.SET, "^Buchungsdatum: (?<bookingDate>\\S+)$", null);
  private static final ParseRule BOOKING =
      new ParseRule(
          ParseRule.BOOKING,
          "^(?<counterparty>.*) \\d\\d\\.\\d\\d (?<amount>[\\d.]+,\\d\\d-?)$",
          null);

  private static final List<ParseRule> BANK_B =
      List.of(
          new ParseRule(
              ParseRule.HEADER,
              "Alter Kontostand vom (?<periodStart>\\S+) (?<openingBalance>\\S+)",
              null),
          new ParseRule(
              ParseRule.HEADER,
              "Neuer Kontostand vom (?<periodEnd>\\S+) (?<closingBalance>\\S+)",
              null),
          new ParseRule(ParseRule.REMOVE_BEFORE, "^Angaben zu den Umsätzen", null),
          new ParseRule(ParseRule.REMOVE_FROM, "^Guthaben sind als Einlagen", null),
          new ParseRule(ParseRule.REMOVE_BETWEEN, "^Folgeseite", "^Angaben zu den Umsätzen"),
          BOOKING_DATE,
          BOOKING);

  private final ParseRuleEngine engine = new ParseRuleEngine();
  private final ToonStatementDecoder decoder = new ToonStatementDecoder();

  @Test
  void readsBankStyleStatementIntoHeaderAndLines() {
    ParsedStatement parsed = parse(STATEMENT, BANK_B, SETTINGS);

    assertThat(parsed.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(parsed.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(parsed.openingBalance()).isEqualByComparingTo("42823.20");
    assertThat(parsed.closingBalance()).isEqualByComparingTo("44714.41");
    assertThat(parsed.lines()).hasSize(3);

    StatementLine first = parsed.lines().get(0).line();
    assertThat(first.bookingDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    assertThat(first.valueDate()).isEqualTo(LocalDate.of(2026, 8, 3));
    assertThat(first.amount()).isEqualByComparingTo("-6.80");
    assertThat(first.counterparty()).isEqualTo("SHOPAAA AUTOROUTE/SOMEWHERE");
    assertThat(first.description()).isEqualTo("2026-07-29T15:24:51 KFN 0 VJ 2912 Kartenzahlung");
    assertThat(first.problem()).isNull();

    StatementLine second = parsed.lines().get(1).line();
    assertThat(second.amount()).isEqualByComparingTo("-1208.79");
    assertThat(second.description()).isEqualTo("Kartenzahlung");

    StatementLine third = parsed.lines().get(2).line();
    assertThat(third.bookingDate()).isEqualTo(LocalDate.of(2026, 8, 4));
    assertThat(third.amount()).isEqualByComparingTo("3100.00");
    assertThat(third.counterparty()).isEqualTo("EMPLOYER AAA GMBH");
    assertThat(third.description()).isEqualTo("Gehalt August");
  }

  @Test
  void keepsPeriodStartAsPrintedWithoutPlusOneDay() {
    ParsedStatement parsed =
        parse(STATEMENT, BANK_B, settingsWith(SETTINGS.continuation(), null, false));

    assertThat(parsed.periodStart()).isEqualTo(LocalDate.of(2026, 7, 31));
  }

  @Test
  void headerRuleTakesFirstMatchPerField() {
    String text =
        """
        Saldo 100,00
        Saldo 200,00
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        """;
    List<ParseRule> rules =
        List.of(
            new ParseRule(ParseRule.HEADER, "^Saldo (?<openingBalance>\\S+)$", null),
            BOOKING_DATE,
            BOOKING);

    assertThat(parse(text, rules, SETTINGS).openingBalance()).isEqualByComparingTo("100.00");
  }

  @Test
  void headerFieldComesFromFirstLineInTextNotFirstRule() {
    String text =
        """
        Saldo neu 100,00
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        Endsaldo 200,00
        """;
    List<ParseRule> rules =
        List.of(
            new ParseRule(ParseRule.HEADER, "^Endsaldo (?<closingBalance>\\S+)$", null),
            new ParseRule(ParseRule.HEADER, "^Saldo neu (?<closingBalance>\\S+)$", null),
            BOOKING_DATE,
            BOOKING);

    assertThat(parse(text, rules, SETTINGS).closingBalance()).isEqualByComparingTo("100.00");
  }

  @Test
  void blankLinesAreNeverBookings() {
    String text = "Buchungsdatum: 03.08.2026\n\nSHOPAAA\n   \n";
    List<ParseRule> rules =
        List.of(BOOKING_DATE, new ParseRule(ParseRule.BOOKING, "^(?<counterparty>.*)$", null));

    assertThat(counterparties(parse(text, rules, SETTINGS))).containsExactly("SHOPAAA");
  }

  @Test
  void headerRulesSeeTextThatRemoveRulesCut() {
    String text =
        """
        Saldo 100,00
        Angaben
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        """;
    List<ParseRule> rules =
        List.of(
            new ParseRule(ParseRule.REMOVE_BEFORE, "^Angaben", null),
            new ParseRule(ParseRule.HEADER, "^Saldo (?<openingBalance>\\S+)$", null),
            BOOKING_DATE,
            BOOKING);

    ParsedStatement parsed = parse(text, rules, SETTINGS);

    assertThat(parsed.openingBalance()).isEqualByComparingTo("100.00");
    assertThat(parsed.lines()).hasSize(1);
  }

  @Test
  void removeBeforeCutsToFirstMatchAndRemoveFromCutsFromLastMatch() {
    String text =
        """
        Letterhead
        CUT
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        CUT
        SHOPBBB 03.08 2,00-
        END
        SHOPCCC 03.08 3,00-
        END
        Footer SHOPDDD 03.08 4,00-
        """;
    List<ParseRule> rules =
        List.of(
            new ParseRule(ParseRule.REMOVE_BEFORE, "^CUT$", null),
            new ParseRule(ParseRule.REMOVE_FROM, "^END$", null),
            BOOKING_DATE,
            BOOKING);

    assertThat(counterparties(parse(text, rules, SETTINGS)))
        .containsExactly("SHOPAAA", "SHOPBBB", "SHOPCCC");
  }

  @Test
  void removeBetweenCutsEveryOccurrenceInclusive() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        PAGE
        SHOPXXX 03.08 9,00-
        TOP
        SHOPBBB 03.08 2,00-
        PAGE
        SHOPYYY 03.08 9,00-
        TOP
        SHOPCCC 03.08 3,00-
        """;
    List<ParseRule> rules =
        List.of(new ParseRule(ParseRule.REMOVE_BETWEEN, "^PAGE$", "^TOP$"), BOOKING_DATE, BOOKING);

    assertThat(counterparties(parse(text, rules, SETTINGS)))
        .containsExactly("SHOPAAA", "SHOPBBB", "SHOPCCC");
  }

  @Test
  void removeBetweenWithoutEndLeavesTheRestAlone() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        PAGE
        SHOPBBB 03.08 2,00-
        """;
    List<ParseRule> rules =
        List.of(new ParseRule(ParseRule.REMOVE_BETWEEN, "^PAGE$", "^TOP$"), BOOKING_DATE, BOOKING);

    assertThat(counterparties(parse(text, rules, SETTINGS))).containsExactly("SHOPAAA", "SHOPBBB");
  }

  @Test
  void bookingsOwnCaptureWinsOverSetValue() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 05.08.2026 1,00-
        SHOPBBB 2,00-
        """;
    List<ParseRule> rules =
        List.of(
            BOOKING_DATE,
            new ParseRule(
                ParseRule.BOOKING,
                "^(?<counterparty>\\S+) (?<bookingDate>\\d\\d\\.\\d\\d\\.\\d{4}) (?<amount>\\S+)$",
                null),
            new ParseRule(ParseRule.BOOKING, "^(?<counterparty>\\S+) (?<amount>\\S+)$", null));

    List<ParsedLine> lines = parse(text, rules, SETTINGS).lines();

    assertThat(lines.get(0).line().bookingDate()).isEqualTo(LocalDate.of(2026, 8, 5));
    assertThat(lines.get(1).line().bookingDate()).isEqualTo(LocalDate.of(2026, 8, 3));
  }

  @Test
  void setRuleIsTriedBeforeBookingRule() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        """;
    List<ParseRule> rules =
        List.of(
            new ParseRule(ParseRule.BOOKING, "^(?<counterparty>.*)$", null),
            new ParseRule(ParseRule.SET, "^Buchungsdatum: (?<bookingDate>\\S+)$", null));

    List<ParsedLine> lines = parse(text, rules, SETTINGS).lines();

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).line().counterparty()).isEqualTo("SHOPAAA 03.08 1,00-");
    assertThat(lines.get(0).line().bookingDate()).isEqualTo(LocalDate.of(2026, 8, 3));
  }

  @Test
  void capturedDescriptionIsFollowedByContinuationLines() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 1,00- Card
        payment
        """;
    List<ParseRule> rules =
        List.of(
            BOOKING_DATE,
            new ParseRule(
                ParseRule.BOOKING,
                "^(?<counterparty>\\S+) (?<amount>\\S+) (?<description>.*)$",
                null));

    assertThat(parse(text, rules, SETTINGS).lines().get(0).line().description())
        .isEqualTo("Card payment");
  }

  @Test
  void continuationToNextBookingAttachesTheLinesAbove() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        for SHOPAAA
        SHOPAAA 03.08 1,00-
        for SHOPBBB
        SHOPBBB 03.08 2,00-
        trailing, nothing below it
        """;

    List<ParsedLine> lines =
        parse(
                text,
                List.of(BOOKING_DATE, BOOKING),
                settingsWith(ParseRuleSettings.CONTINUATION_NEXT, null, false))
            .lines();

    assertThat(lines.get(0).line().description()).isEqualTo("for SHOPAAA");
    assertThat(lines.get(1).line().description()).isEqualTo("for SHOPBBB");
  }

  @Test
  void continuationToPreviousDropsLinesBeforeTheFirstBooking() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        nothing above it
        SHOPAAA 03.08 1,00-
        for SHOPAAA
        """;

    List<ParsedLine> lines = parse(text, List.of(BOOKING_DATE, BOOKING), SETTINGS).lines();

    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).line().description()).isEqualTo("for SHOPAAA");
  }

  @Test
  void continuationOffDropsEveryUnmatchedLine() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        Kartenzahlung
        """;

    List<ParsedLine> lines =
        parse(
                text,
                List.of(BOOKING_DATE, BOOKING),
                settingsWith(ParseRuleSettings.CONTINUATION_OFF, null, false))
            .lines();

    assertThat(lines.get(0).line().description()).isNull();
  }

  @Test
  void smartSpaceJoinsLinesWrappedAtTheColumnWidth() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 1,00-
        abcdefghij
        klm
        nop
        """;

    List<ParsedLine> lines =
        parse(
                text,
                List.of(BOOKING_DATE, BOOKING),
                settingsWith(ParseRuleSettings.CONTINUATION_PREVIOUS, 10, false))
            .lines();

    assertThat(lines.get(0).line().description()).isEqualTo("abcdefghijklm nop");
  }

  @Test
  void readsLeadingMinusAndOtherSeparators() {
    String text =
        """
        2026-08-03;ShopAaa;-1,234.50
        2026-08-04;ShopBbb;99.00
        """;
    ParseRuleSettings english =
        new ParseRuleSettings(
            ",",
            ".",
            ParseRuleSettings.MINUS_LEADING,
            "yyyy-MM-dd",
            ParseRuleSettings.CONTINUATION_OFF,
            null,
            false);
    List<ParseRule> rules =
        List.of(
            new ParseRule(
                ParseRule.BOOKING,
                "^(?<bookingDate>[^;]+);(?<counterparty>[^;]+);(?<amount>[^;]+)$",
                null));

    List<ParsedLine> lines = parse(text, rules, english).lines();

    assertThat(lines.get(0).line().amount()).isEqualByComparingTo("-1234.50");
    assertThat(lines.get(1).line().amount()).isEqualByComparingTo("99.00");
  }

  @Test
  void readsForeignChargeFields() {
    String text =
        """
        Buchungsdatum: 03.08.2026
        SHOPAAA 03.08 9,17- USD 10,00 Kurs 1,0905
        """;
    List<ParseRule> rules =
        List.of(
            BOOKING_DATE,
            new ParseRule(
                ParseRule.BOOKING,
                "^(?<counterparty>\\S+) \\S+ (?<amount>\\S+) (?<originalCurrency>[A-Z]{3}) "
                    + "(?<originalAmount>\\S+) Kurs (?<originalRate>\\S+)$",
                null));

    ParsedLine line = parse(text, rules, SETTINGS).lines().get(0);

    assertThat(line.originalCurrency()).isEqualTo("USD");
    assertThat(line.originalAmount()).isEqualByComparingTo("10.00");
    assertThat(line.originalRate()).isEqualByComparingTo("1.0905");
  }

  @Test
  void valueThatWillNotConvertBecomesProblemLine() {
    String text =
        """
        Buchungsdatum: 3.8.26
        SHOPAAA 03.08 1,00-
        SHOPBBB 03.08 -2,00
        """;
    List<ParseRule> rules =
        List.of(
            BOOKING_DATE,
            new ParseRule(
                ParseRule.BOOKING,
                "^(?<counterparty>\\S+) \\d\\d\\.\\d\\d (?<amount>\\S+)$",
                null));

    List<ParsedLine> lines = parse(text, rules, SETTINGS).lines();

    assertThat(lines.get(0).line().problem()).contains("booking date");
    assertThat(lines.get(0).line().rawText()).contains("3.8.26");
    assertThat(lines.get(1).line().problem()).isNotNull();
  }

  @Test
  void bookingWithoutBookingDateIsProblemLine() {
    String text = "SHOPAAA 03.08 1,00-\n";

    ParsedLine line = parse(text, List.of(BOOKING_DATE, BOOKING), SETTINGS).lines().get(0);

    assertThat(line.line().bookingDate()).isNull();
    assertThat(line.line().problem()).contains("booking date");
  }

  @Test
  void textWithoutBookingsYieldsEmptyLines() {
    ParsedStatement parsed = parse("Nothing here\n", List.of(BOOKING_DATE, BOOKING), SETTINGS);

    assertThat(parsed.lines()).isEmpty();
    assertThat(parsed.periodStart()).isNull();
  }

  @Test
  void validRulesAndSettingsHaveNoProblems() {
    assertThat(engine.problems(BANK_B, SETTINGS)).isEmpty();
  }

  @Test
  void flagsInvalidRegex() {
    assertThat(engine.problem(new ParseRule(ParseRule.BOOKING, "(?<amount>[", null)))
        .hasValueSatisfying(problem -> assertThat(problem).startsWith("Invalid regex"));
  }

  @Test
  void flagsInvalidEndRegex() {
    assertThat(engine.problem(new ParseRule(ParseRule.REMOVE_BETWEEN, "^A$", "(")))
        .hasValueSatisfying(problem -> assertThat(problem).startsWith("Invalid end regex"));
  }

  @Test
  void flagsBlankPattern() {
    assertThat(engine.problem(new ParseRule(ParseRule.SET, " ", null))).isPresent();
  }

  @Test
  void flagsUnknownKind() {
    assertThat(engine.problem(new ParseRule("guess", "^x$", null))).isPresent();
  }

  @Test
  void flagsUnknownGroupName() {
    assertThat(engine.problem(new ParseRule(ParseRule.BOOKING, "(?<payee>.*)", null)))
        .hasValueSatisfying(problem -> assertThat(problem).contains("payee"));
  }

  @Test
  void flagsLineFieldOnHeaderRuleAndHeaderFieldOnBookingRule() {
    assertThat(engine.problem(new ParseRule(ParseRule.HEADER, "(?<amount>.*)", null))).isPresent();
    assertThat(engine.problem(new ParseRule(ParseRule.BOOKING, "(?<periodEnd>.*)", null)))
        .isPresent();
  }

  @Test
  void flagsBetweenRuleWithoutEnd() {
    assertThat(engine.problem(new ParseRule(ParseRule.REMOVE_BETWEEN, "^A$", " ")))
        .hasValueSatisfying(problem -> assertThat(problem).contains("end pattern"));
  }

  @Test
  void flagsRuleSetWithoutBookingRule() {
    assertThat(engine.problems(List.of(BOOKING_DATE), SETTINGS))
        .containsExactly("Add at least one booking rule.");
  }

  @Test
  void numbersProblemsByRulePosition() {
    List<ParseRule> rules = new ArrayList<>(BANK_B);
    rules.add(new ParseRule(ParseRule.SET, "(", null));

    assertThat(engine.problems(rules, SETTINGS))
        .singleElement()
        .satisfies(problem -> assertThat(problem).startsWith("Rule 8: "));
  }

  @Test
  void flagsInvalidSettings() {
    ParseRuleSettings bad =
        new ParseRuleSettings(",", ",,", "middle", "dd{MM", "sideways", 0, false);

    assertThat(engine.problems(BANK_B, bad)).hasSize(5);
  }

  @Test
  void flagsSeparatorThatCouldBeDigitOrMinus() {
    ParseRuleSettings minus =
        new ParseRuleSettings(
            "-",
            "0",
            ParseRuleSettings.MINUS_TRAILING,
            "dd.MM.yyyy",
            ParseRuleSettings.CONTINUATION_OFF,
            null,
            false);

    assertThat(engine.problems(BANK_B, minus))
        .containsExactly(
            "The decimal separator must be , or .",
            "The thousands separator must be . , ' or a space, or empty for none.");
  }

  @Test
  void flagsSameThousandsAndDecimalSeparator() {
    ParseRuleSettings same =
        new ParseRuleSettings(
            ",",
            ",",
            ParseRuleSettings.MINUS_TRAILING,
            "dd.MM.yyyy",
            ParseRuleSettings.CONTINUATION_OFF,
            null,
            false);

    assertThat(engine.problems(BANK_B, same)).hasSize(1);
  }

  @Test
  void refusesToRunInvalidRules() {
    List<ParseRule> rules = List.of(BOOKING_DATE);

    assertThatThrownBy(() -> engine.toToon(STATEMENT, rules, SETTINGS))
        .isInstanceOf(StatementFormatException.class)
        .hasMessageContaining("booking rule");
  }

  private ParsedStatement parse(String text, List<ParseRule> rules, ParseRuleSettings settings) {
    return decoder.decode(engine.toToon(text, rules, settings)).orElseThrow();
  }

  private static ParseRuleSettings settingsWith(
      String continuation, Integer smartSpaceWidth, boolean periodStartPlusOne) {
    return new ParseRuleSettings(
        ".",
        ",",
        ParseRuleSettings.MINUS_TRAILING,
        "dd.MM.yyyy",
        continuation,
        smartSpaceWidth,
        periodStartPlusOne);
  }

  private static List<String> counterparties(ParsedStatement parsed) {
    return parsed.lines().stream().map(line -> line.line().counterparty()).toList();
  }
}
