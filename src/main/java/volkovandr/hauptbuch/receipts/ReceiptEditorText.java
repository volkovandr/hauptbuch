package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import volkovandr.hauptbuch.operations.SplitLineAmounts;

/**
 * The small, shared text/number coercions of the post-process editor (plan §9f) — lenient by design
 * (a draft never rejects a half-typed field): a blank amount reads as zero, an unparseable date as
 * null. Kept in one place so the assembler (readout) and the service (Save) coerce identically.
 */
final class ReceiptEditorText {

  private ReceiptEditorText() {}

  static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** Parse a German amount keeping its storno sign; a blank or unparseable value reads as zero. */
  static BigDecimal parse(String text) {
    if (text == null || text.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return SplitLineAmounts.parseSignedAmount(text);
    } catch (IllegalArgumentException e) {
      return BigDecimal.ZERO;
    }
  }

  /** Parse an ISO date; a blank or unparseable value reads as null. */
  static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.strip());
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
