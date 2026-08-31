package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;

/**
 * Parses a QIF amount field ({@code T}, {@code U}, or a split {@code $}) into a {@link BigDecimal}
 * — one routine for all three, since Money's export writes every one of them with the same
 * thousands-grouping comma (import.md §4.2, confirmed against a real export, e.g. {@code
 * T-650,000.00}). The value carries no currency: QIF names none, so a canonical amount stays a
 * plain decimal until a currency is chosen at the account map (§5.1).
 */
final class QifAmounts {

  private QifAmounts() {}

  /** Parse a raw amount field's value (the text after its leading field letter). */
  static BigDecimal parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new QifRejectedException("A QIF amount field is blank.");
    }
    try {
      return new BigDecimal(raw.strip().replace(",", ""));
    } catch (NumberFormatException e) {
      throw new QifRejectedException("Not a valid QIF amount: \"" + raw + "\".", e);
    }
  }
}
