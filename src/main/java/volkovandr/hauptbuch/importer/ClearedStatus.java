package volkovandr.hauptbuch.importer;

/**
 * A QIF {@code C} field, classified (import.md §4.2): {@code *}/{@code c} → cleared, {@code X}/
 * {@code R} → reconciled, absent → unreconciled. This is the canonical, source-vocabulary value —
 * it lands as {@code posting.reconciliation} once booked, which is out of scope here.
 */
public enum ClearedStatus {
  UNRECONCILED,
  CLEARED,
  RECONCILED;

  /**
   * Classify a raw {@code C} field value (the text after the leading {@code C}), or {@link
   * #UNRECONCILED} when the field was absent (pass {@code null}).
   */
  static ClearedStatus fromCode(String code) {
    if (code == null || code.isBlank()) {
      return UNRECONCILED;
    }
    return switch (code.strip()) {
      case "*", "c" -> CLEARED;
      case "X", "R" -> RECONCILED;
      default -> throw new QifRejectedException("Unrecognised QIF cleared flag: \"" + code + "\".");
    };
  }
}
