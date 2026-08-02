package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tier (plan §1.5): the display derivations on {@link Receipt} (stage 9e, owner feedback
 * 2026-08-02). {@code merchantDisplay} joins the parsed merchant name, city, and country the way
 * the register shows a payee, dropping the blanks.
 */
class ReceiptTest {

  private static Receipt merchant(String text, String city, String country) {
    return new Receipt(
        1L,
        "processed",
        null,
        "pc",
        "orig.jpg",
        "edit.jpg",
        "{}",
        null,
        null,
        null,
        text,
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
        city,
        country,
        null,
        null,
        null);
  }

  @Test
  void joinsNameCityCountryWithDashes() {
    assertThat(merchant("Total Tankstelle", "Berlin", "Germany").merchantDisplay())
        .isEqualTo("Total Tankstelle - Berlin - Germany");
  }

  @Test
  void dropsBlankParts() {
    assertThat(merchant("Total Tankstelle", null, "Germany").merchantDisplay())
        .isEqualTo("Total Tankstelle - Germany");
    assertThat(merchant("Total Tankstelle", "  ", null).merchantDisplay())
        .isEqualTo("Total Tankstelle");
  }

  @Test
  void nullWhenNoMerchantPart() {
    assertThat(merchant(null, null, null).merchantDisplay()).isNull();
  }
}
