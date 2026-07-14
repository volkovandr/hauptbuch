package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import volkovandr.hauptbuch.ledger.TransferTarget;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The split panel's amount-sign math (register §3.8, plan stage 7c.2/7d.3) — the pure, stateless
 * functions that turn a split line's <em>typed</em> German-formatted amount into a <em>signed</em>
 * contribution, and back. Extracted from {@link DockSplitService} (which orchestrates the postings)
 * so that service stays cohesive: these have no dependency on the ledger, the accounts, or any
 * collaborator, and are shared by the commit path, {@link SplitPanelAssembler}'s live readout, and
 * {@link SplitFormBinder}.
 *
 * <p><strong>The mixed-split sign rule (ratified 2026-07-09).</strong> A line's signed contribution
 * is {@code +amount} for an income category and {@code −amount} for an expense one, where {@code
 * amount} is the number the user typed <em>already signed</em>, so a leading {@code −} (a storno)
 * flows through: a negative on an income line counts negatively, on an expense line positively. A
 * <em>transfer</em> line (register §3.8, plan stage 7d.3) has no category type — its direction
 * signs it: {@code TO} is an outflow (like an expense), {@code FROM} an inflow (like income).
 */
final class SplitLineAmounts {

  private static final String INCOME = "income";

  /** German entry is to the minor unit; two places covers EUR/CHF. */
  private static final int FRACTION_DIGITS = 2;

  /** The Unicode minus sign, accepted as a storno marker alongside the ASCII hyphen-minus. */
  private static final char UNICODE_MINUS = '−';

  private SplitLineAmounts() {}

  /**
   * A category line's signed contribution to the funding sum (the mixed-split rule): the typed
   * amount kept with its own sign, then made positive for an income category and negative for an
   * expense one. A storno (a leading {@code −}) therefore flips: negative on income counts
   * negatively, negative on expense counts positively.
   *
   * @param amountText the typed amount, German-formatted, optionally a leading {@code −} storno
   * @param categoryType the resolved leaf's type ({@code income}/{@code expense})
   */
  static BigDecimal signedContribution(String amountText, String categoryType) {
    BigDecimal value = parseSignedAmount(amountText);
    return INCOME.equals(categoryType) ? value : value.negate();
  }

  /**
   * A transfer line's signed contribution to the funding sum (register §3.8): the typed amount kept
   * with its own sign (a storno flows through), made negative for a {@code TO} transfer (an
   * outflow, like an expense) and positive for a {@code FROM} one (an inflow, like income) — the
   * transfer analogue of {@link #signedContribution}, which reads a category type.
   */
  static BigDecimal transferContribution(String amountText, String direction) {
    BigDecimal value = parseSignedAmount(amountText);
    return TransferTarget.Direction.FROM.name().equals(direction) ? value : value.negate();
  }

  /**
   * A split line's signed contribution for the panel's <em>live readout</em> (the lenient sibling
   * of {@link #signedContribution}/{@link #transferContribution} used by {@link
   * SplitPanelAssembler}): zero for an incomplete line — blank amount, or neither a resolved
   * category type nor a transfer direction — and zero for mid-entry unparseable text, so the panel
   * renders sensibly before a line is complete. The commit path re-derives the same numbers
   * strictly.
   */
  static BigDecimal lenientContribution(String amount, String type, String direction) {
    if (amount == null || amount.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      if (direction != null && !direction.isBlank()) {
        return transferContribution(amount, direction);
      }
      if (type == null || type.isBlank()) {
        return BigDecimal.ZERO;
      }
      return signedContribution(amount, type);
    } catch (IllegalArgumentException e) {
      return BigDecimal.ZERO;
    }
  }

  /**
   * Parse a line amount keeping its sign: a bare magnitude is positive; a leading {@code −} (ASCII
   * or Unicode) makes it negative (a storno); a leading {@code +} is accepted and redundant. Unlike
   * the simple dock's sign resolution, there is no direction <em>override</em> here — the category
   * type (or transfer direction) decides direction, and the sign is only the storno.
   */
  static BigDecimal parseSignedAmount(String amountText) {
    if (amountText == null || amountText.isBlank()) {
      throw new IllegalArgumentException("A line amount is required");
    }
    String trimmed = amountText.strip();
    char first = trimmed.charAt(0);
    boolean negative = first == '-' || first == UNICODE_MINUS;
    boolean signed = negative || first == '+';
    String magnitudeText = signed ? trimmed.substring(1).strip() : trimmed;

    BigDecimal magnitude = MoneyFormat.parse(magnitudeText).abs();
    return negative ? magnitude.negate() : magnitude;
  }

  /**
   * The magnitude the user would type for a category leg (the mixed-split rule), inverting {@link
   * #signedContribution}: an income leg's typed value is {@code −amount}, an expense leg's is
   * {@code +amount}; a negative result is a storno and carries a leading {@code −}.
   */
  static String amountText(BigDecimal legAmount, String categoryType) {
    BigDecimal typed = INCOME.equals(categoryType) ? legAmount.negate() : legAmount;
    String magnitude = MoneyFormat.number(typed.abs(), FRACTION_DIGITS);
    return typed.signum() < 0 ? "-" + magnitude : magnitude;
  }
}
