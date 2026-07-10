package volkovandr.hauptbuch.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.joda.money.Money;
import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): no container, pure logic. Pins the German display/parse contract (register
 * §2.9, NFR-08): German grouping/decimal ({@code 1.234,56}); the base currency rendered bare; a
 * non-base currency carries its symbol/ISO.
 */
class MoneyFormatTest {

  /** The book's base currency for these cases; a base-currency value renders bare. */
  private static final String BASE = "EUR";

  /** A canonical four-figure magnitude, used across the display and parse round-trip cases. */
  private static final String MAGNITUDE = "1234.56";

  @Test
  void formatsGermanGroupingAndDecimal() {
    // EUR is the base here, so it renders bare — just the German-formatted number.
    assertThat(MoneyFormat.display(MoneyFactory.of(new BigDecimal(MAGNITUDE), BASE), BASE))
        .isEqualTo("1.234,56");
  }

  @Test
  void rendersNegativeWithLeadingMinus() {
    assertThat(MoneyFormat.display(MoneyFactory.of(new BigDecimal("-1234.56"), BASE), BASE))
        .isEqualTo("-1.234,56");
  }

  @Test
  void baseCurrencyIsRenderedBare() {
    assertThat(MoneyFormat.display(MoneyFactory.of(new BigDecimal("50"), BASE), BASE))
        .isEqualTo("50,00");
  }

  @Test
  void nonBaseCurrencyCarriesItsSymbol() {
    // CHF is not base here; it carries its currency symbol after the German number.
    assertThat(MoneyFormat.display(MoneyFactory.of(new BigDecimal(MAGNITUDE), "CHF"), BASE))
        .isEqualTo("1.234,56 CHF");
  }

  @Test
  void respectsZeroMinorUnitsCurrency() {
    // JPY has zero minor units; no decimal part, no grouping artifacts.
    assertThat(MoneyFormat.display(MoneyFactory.of(new BigDecimal("1234"), "JPY"), BASE))
        .isEqualTo("1.234 ¥");
  }

  @Test
  void formatsBareNumberWithoutAnyCurrency() {
    // The number-only helper (for inputs / non-money quantities) is currency-agnostic.
    assertThat(MoneyFormat.number(new BigDecimal("1234.5"), 2)).isEqualTo("1.234,50");
  }

  @Test
  void parsesGermanFormattedInput() {
    assertThat(MoneyFormat.parse("1.234,56")).isEqualByComparingTo("1234.56");
  }

  @Test
  void parsesTolerantlyWithoutGrouping() {
    assertThat(MoneyFormat.parse("1234,56")).isEqualByComparingTo("1234.56");
  }

  @Test
  void parsesPlainIntegerInput() {
    assertThat(MoneyFormat.parse("50")).isEqualByComparingTo("50");
  }

  @Test
  void parsesDotAsDecimalSeparator() {
    // A numeric-keypad dot is honoured as the decimal point (owner decision, 2026-07-09): "15.50"
    // is
    // fifteen-fifty, not one-thousand-five-hundred-fifty.
    assertThat(MoneyFormat.parse("15.50")).isEqualByComparingTo("15.50");
  }

  @Test
  void parsesAngloGroupingWithDotDecimal() {
    // The last separator is the decimal; the earlier one is grouping and dropped.
    assertThat(MoneyFormat.parse("1,234.56")).isEqualByComparingTo("1234.56");
  }

  @Test
  void parsesNegativeDotDecimal() {
    assertThat(MoneyFormat.parse("-15.50")).isEqualByComparingTo("-15.50");
  }

  @Test
  void parseRejectsUnparseableInput() {
    assertThatThrownBy(() -> MoneyFormat.parse("not a number"))
        .isInstanceOf(NumberFormatException.class);
  }

  @Test
  void displayRejectsNullMoney() {
    assertThatThrownBy(() -> MoneyFormat.display((Money) null, BASE))
        .isInstanceOf(NullPointerException.class);
  }
}
