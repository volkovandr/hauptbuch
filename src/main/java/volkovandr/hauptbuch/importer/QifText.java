package volkovandr.hauptbuch.importer;

/**
 * Small text predicates shared across the QIF parser. Money destroys any character it cannot
 * represent in windows-1252 with a literal {@code ?} on export (import.md §4.4), so free-text
 * fields — payee names, class names, and legacy account names — can come through as nothing but
 * {@code ?}s and whitespace.
 */
final class QifText {

  private QifText() {}

  /**
   * True when {@code text} is non-empty and made up entirely of {@code ?} and whitespace — i.e. it
   * was wholly destroyed on export and carries no distinguishing information.
   */
  static boolean isDestroyed(String text) {
    return !text.isEmpty()
        && text.chars()
            .allMatch(codePoint -> codePoint == '?' || Character.isWhitespace(codePoint));
  }

  /** {@code null} for a null or blank string, otherwise the string unchanged. */
  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
