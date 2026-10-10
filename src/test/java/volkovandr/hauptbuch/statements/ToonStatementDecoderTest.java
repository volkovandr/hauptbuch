package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.statements.ParsedStatement.ParsedLine;

/**
 * Unit tier: the lenient TOON decode of a statement — the header, the lines, the foreign charge, a
 * line it cannot read kept with a problem, and a body that is not TOON at all.
 */
class ToonStatementDecoderTest {

  private static final String HEADER =
      """
      statement:
        periodStart: 2026-05-01
        periodEnd: 2026-05-31
        openingBalance: 1200.50
        closingBalance: 1138.00
      """;
  private static final String COLUMNS =
      "lines[%d]{bookingDate,valueDate,amount,counterparty,description,bankCategory,"
          + "originalAmount,originalCurrency,originalRate}:%n";

  private final ToonStatementDecoder decoder = new ToonStatementDecoder();

  @Test
  void decodesHeaderAndLines() {
    String body =
        HEADER
            + COLUMNS.formatted(2)
            + "  2026-05-02,2026-05-03,-12.50,ShopAaa,Card payment,Groceries,,,\n"
            + "  2026-05-09,,-50.00,\"ShopBbb, Ltd\",Card 55.00 USD,Shopping,55.00,usd,1.10\n";

    ParsedStatement parsed = decoder.decode(body).orElseThrow();

    assertThat(parsed.periodStart()).isEqualTo(LocalDate.of(2026, 5, 1));
    assertThat(parsed.periodEnd()).isEqualTo(LocalDate.of(2026, 5, 31));
    assertThat(parsed.openingBalance()).isEqualByComparingTo("1200.50");
    assertThat(parsed.closingBalance()).isEqualByComparingTo("1138.00");
    assertThat(parsed.lines()).hasSize(2);
    StatementLine first = parsed.lines().get(0).line();
    assertThat(first.sortOrder()).isZero();
    assertThat(first.bookingDate()).isEqualTo(LocalDate.of(2026, 5, 2));
    assertThat(first.valueDate()).isEqualTo(LocalDate.of(2026, 5, 3));
    assertThat(first.amount()).isEqualByComparingTo("-12.50");
    assertThat(first.counterparty()).isEqualTo("ShopAaa");
    assertThat(first.bankCategory()).isEqualTo("Groceries");
    assertThat(first.problem()).isNull();
    assertThat(first.rawText()).contains("ShopAaa").contains(" | ");
    ParsedLine second = parsed.lines().get(1);
    assertThat(second.line().sortOrder()).isEqualTo(1);
    assertThat(second.line().valueDate()).isNull();
    assertThat(second.line().counterparty()).isEqualTo("ShopBbb, Ltd");
    assertThat(second.originalAmount()).isEqualByComparingTo("55.00");
    assertThat(second.originalCurrency()).isEqualTo("USD");
    assertThat(second.originalRate()).isEqualByComparingTo(new BigDecimal("1.10"));
  }

  @Test
  void keepsLineWithUnreadableDateOrAmountAsProblem() {
    String body =
        HEADER
            + COLUMNS.formatted(2)
            + "  02.05.2026,,-12.50,ShopAaa,x,,,,\n"
            + "  2026-05-03,,twelve,ShopBbb,y,,,,\n";

    ParsedStatement parsed = decoder.decode(body).orElseThrow();

    assertThat(parsed.lines()).hasSize(2);
    assertThat(parsed.lines().get(0).line().problem()).contains("date");
    assertThat(parsed.lines().get(0).line().bookingDate()).isNull();
    assertThat(parsed.lines().get(1).line().problem()).contains("amount");
  }

  @Test
  void dropsUnreadableHeaderValueAndToleratesMissingHeader() {
    ParsedStatement badHeader =
        decoder
            .decode(
                "statement:\n  periodStart: soon\n  openingBalance: lots\n" + COLUMNS.formatted(0))
            .orElseThrow();
    assertThat(badHeader.periodStart()).isNull();
    assertThat(badHeader.openingBalance()).isNull();
    assertThat(badHeader.lines()).isEmpty();

    ParsedStatement noHeader =
        decoder.decode(COLUMNS.formatted(1) + "  2026-05-02,,-1.00,A,B,,,,\n").orElseThrow();
    assertThat(noHeader.periodEnd()).isNull();
    assertThat(noHeader.lines()).hasSize(1);
  }

  @Test
  void bodyWithoutLinesTableIsUndecodable() {
    assertThat(decoder.decode(HEADER)).isEmpty();
  }

  @Test
  void stripsCodeFence() {
    Optional<ParsedStatement> parsed =
        decoder.decode("```toon\n" + HEADER + COLUMNS.formatted(0) + "```");

    assertThat(parsed).isPresent();
    assertThat(parsed.orElseThrow().closingBalance()).isEqualByComparingTo("1138.00");
  }

  @Test
  void anUndecodableBodyIsEmpty() {
    assertThat(decoder.decode(null)).isEmpty();
    assertThat(decoder.decode("  ")).isEmpty();
    assertThat(decoder.decode("lines[2]{a,b}:\n  only-one-cell\n")).isEmpty();
  }
}
