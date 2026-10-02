package volkovandr.hauptbuch.operations;

/**
 * The mapping between the dock's amount <em>fields</em> and its two leg amounts (issue
 * transaction-register-ui/04). The dock shows the transaction-currency {@code Amount} first and,
 * for a cross-currency entry, the account-currency {@code Off account} after it; the commit and
 * edit paths ({@link DockEntry}, {@link DockEditModel}) keep thinking in legs — the funding leg's
 * signed amount and the counterpart leg's sign-free magnitude. This class is the one place the two
 * views meet.
 *
 * <p>The explicit-sign override (register §3.8) is typed on the field the operator types first, the
 * {@code Amount}; it belongs to the funding leg, so it moves onto the {@code Off account} text on
 * the way in and back onto the {@code Amount} on the way out. A single-currency entry needs no
 * mapping: its one field <em>is</em> the funding leg's amount.
 */
final class DockAmountTexts {

  private static final char UNICODE_MINUS = '−';

  private DockAmountTexts() {}

  /**
   * The funding leg's amount text for a cross-currency entry: the {@code Off account} magnitude,
   * carrying the explicit sign (if any) typed on the {@code Amount}.
   */
  static String fundingText(String amountText, String offAccountText) {
    return signOf(amountText) + strip(offAccountText);
  }

  /**
   * The counterpart leg's sign-free magnitude for a cross-currency entry: the bare {@code Amount}.
   */
  static String counterpartText(String amountText) {
    return magnitudeOf(amountText);
  }

  /**
   * The {@code Amount} field's text when a cross-currency transaction is loaded for edit: the
   * counterpart magnitude, carrying the funding leg's explicit sign (if any).
   */
  static String amountText(String fundingText, String counterpartText) {
    return signOf(fundingText) + strip(counterpartText);
  }

  /** The {@code Off account} field's text when a cross-currency transaction is loaded for edit. */
  static String offAccountText(String fundingText) {
    return magnitudeOf(fundingText);
  }

  /** The leading {@code +}/{@code −} of a typed amount, or empty when it has none. */
  private static String signOf(String text) {
    String trimmed = strip(text);
    return !trimmed.isEmpty() && isSign(trimmed.charAt(0)) ? trimmed.substring(0, 1) : "";
  }

  /** A typed amount without its leading {@code +}/{@code −}. */
  private static String magnitudeOf(String text) {
    String trimmed = strip(text);
    return !trimmed.isEmpty() && isSign(trimmed.charAt(0)) ? trimmed.substring(1).strip() : trimmed;
  }

  private static boolean isSign(char c) {
    return c == '+' || c == '-' || c == UNICODE_MINUS;
  }

  private static String strip(String text) {
    return text == null ? "" : text.strip();
  }
}
