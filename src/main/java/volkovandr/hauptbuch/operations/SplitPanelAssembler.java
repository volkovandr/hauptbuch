package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Builds the {@link SplitPanel} view model from a {@link SplitForm} on every server round-trip of
 * the split panel (register §3.10, plan stage 7c.2/7d.2) — open, add-line, remove-line, and error
 * redisplay — and owns the "the rest" defaulting when a line is added.
 *
 * <p>The readout math mirrors {@link DockSplitService}'s commit math (the mixed-split rule ratified
 * 2026-07-09) but <em>leniently</em>: an incomplete line (no amount yet, or an unresolved category)
 * simply contributes nothing, so the panel renders sensibly mid-entry. The commit path re-derives
 * the same numbers authoritatively from the resolved leaves — these are a display convenience the
 * keyboard.js leaf also computes live as the user types (§1.7).
 *
 * <p><strong>Cross-currency (register §3.8a/§3.10, plan stage 7d.2).</strong> When the spending
 * selector names a currency other than the funding account's, the panel derives each line's
 * funding-currency and base-currency equivalents from the header's shared rate (funding/spending
 * and base/spending), and prints a {@code remaining} in every currency in play — all proportional
 * to the spending remaining, so they reach zero together. The committed base amounts are frozen
 * with a last-line residual (see {@link DockSplitService}); the readout uses the fixed rate, which
 * agrees to the minor unit once the lines balance.
 */
@Component
class SplitPanelAssembler {

  /** German entry is to the minor unit; two places covers EUR/CHF/USD. */
  private static final int FRACTION_DIGITS = 2;

  /** Intermediate scale for the shared-rate division before rounding derived amounts. */
  private static final int RATE_SCALE = 10;

  private final AccountService accountService;
  private final SettingsService settingsService;

  SplitPanelAssembler(AccountService accountService, SettingsService settingsService) {
    this.accountService = accountService;
    this.settingsService = settingsService;
  }

  /** Build the panel view model for the current form state, optionally carrying a message. */
  SplitPanel panel(SplitForm form, String error) {
    CurrencyContext ctx = resolveCurrencyContext(form);
    int count = lineCount(form);
    List<SplitLineView> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < count; i++) {
      String amount = at(form.lineAmount(), i);
      String type = at(form.lineCategoryType(), i);
      net = net.add(lenientContribution(amount, type));
      BigDecimal magnitude = lenientParse(amount).abs();
      lines.add(
          new SplitLineView(
              i,
              at(form.categoryText(), i),
              at(form.lineCategoryId(), i),
              type,
              amount,
              at(form.lineNote(), i),
              ctx.derived(magnitude, ctx.rateSpendingToFunding()),
              ctx.derived(magnitude, ctx.rateSpendingToBase())));
    }

    BigDecimal total = lenientParse(form.total());
    BigDecimal netMagnitude = net.abs();
    BigDecimal remaining = total.subtract(netMagnitude);
    return new SplitPanel(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.payeeText(),
        form.note(),
        MoneyFormat.number(total, FRACTION_DIGITS),
        currencyView(ctx, netMagnitude),
        lines,
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        remaining.signum() == 0,
        MoneyFormat.number(ctx.fundingNet(netMagnitude), FRACTION_DIGITS),
        direction(net),
        error);
  }

  /** The cross-currency header view for the panel — single-currency when the currencies match. */
  private SplitCurrency currencyView(CurrencyContext ctx, BigDecimal netMagnitude) {
    if (!ctx.cross()) {
      return SplitCurrency.singleCurrency(ctx.funding());
    }
    BigDecimal remainingFunding = ctx.fundingTotal().subtract(ctx.fundingOf(netMagnitude));
    BigDecimal remainingBase =
        ctx.baseTotal().subtract(ctx.derivedValue(netMagnitude, ctx.rateSpendingToBase()));
    return new SplitCurrency(
        true,
        ctx.funding(),
        ctx.spending(),
        ctx.base(),
        ctx.neitherIsBase(),
        MoneyFormat.number(ctx.fundingTotal(), FRACTION_DIGITS),
        MoneyFormat.number(ctx.baseTotal(), FRACTION_DIGITS),
        MoneyFormat.number(remainingFunding, FRACTION_DIGITS),
        MoneyFormat.number(remainingBase, FRACTION_DIGITS),
        ctx.rateSpendingToFunding().toPlainString(),
        ctx.rateSpendingToBase().toPlainString());
  }

  /**
   * Resolve the panel's currency state from the funding account and the spending selector: whether
   * it is cross-currency, the three currencies, and the shared rate (funding-per-spending,
   * base-per-spending) derived from the header totals (register §3.8a).
   */
  private CurrencyContext resolveCurrencyContext(SplitForm form) {
    String funding =
        form.accountId() == null
            ? ""
            : accountService.findById(form.accountId()).map(Account::currencyCode).orElse("");
    String spending = blankToNull(form.spendingCurrencyCode());
    boolean cross = spending != null && !funding.isBlank() && !spending.equals(funding);
    if (!cross) {
      return CurrencyContext.singleCurrency(funding);
    }

    String base = settingsService.baseCurrency().orElse(funding);
    boolean neitherIsBase = !funding.equals(base) && !spending.equals(base);
    BigDecimal totalSpending = lenientParse(form.total());
    BigDecimal fundingTotal = lenientParse(form.fundingTotal());
    BigDecimal baseTotal;
    if (funding.equals(base)) {
      baseTotal = fundingTotal;
    } else if (spending.equals(base)) {
      baseTotal = totalSpending;
    } else {
      baseTotal = lenientParse(form.baseTotal());
    }
    BigDecimal rateSpendingToFunding = ratio(fundingTotal, totalSpending);
    BigDecimal rateSpendingToBase = ratio(baseTotal, totalSpending);
    return new CurrencyContext(
        true,
        funding,
        spending,
        base,
        neitherIsBase,
        fundingTotal,
        baseTotal,
        rateSpendingToFunding,
        rateSpendingToBase);
  }

  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Append a blank line whose amount defaults to "the rest" — {@code total − allocated} in the
   * spending currency (register §3.10) — so the last line closes the gap. Returns a new form; the
   * caller re-renders it.
   */
  SplitForm addLine(SplitForm form) {
    SplitPanel current = panel(form, null);
    BigDecimal remaining = lenientParse(current.remaining());
    String rest = remaining.signum() > 0 ? current.remaining() : "";
    return withLines(
        form,
        appended(form.categoryText(), ""),
        appended(form.lineCategoryId(), ""),
        appended(form.lineCategoryType(), ""),
        appended(form.lineAmount(), rest),
        appended(form.lineNote(), ""));
  }

  /** Remove the line at {@code index} across every aligned array. Returns a new form. */
  SplitForm removeLine(SplitForm form, int index) {
    return withLines(
        form,
        removed(form.categoryText(), index),
        removed(form.lineCategoryId(), index),
        removed(form.lineCategoryType(), index),
        removed(form.lineAmount(), index),
        removed(form.lineNote(), index));
  }

  /** The signed contribution, or zero for an incomplete line (blank amount or unresolved type). */
  private static BigDecimal lenientContribution(String amount, String type) {
    if (amount == null || amount.isBlank() || type == null || type.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return DockSplitService.signedContribution(amount, type);
    } catch (IllegalArgumentException e) {
      return BigDecimal.ZERO; // mid-entry unparseable text contributes nothing to the readout
    }
  }

  private static BigDecimal lenientParse(String text) {
    if (text == null || text.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return MoneyFormat.parse(text);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static String direction(BigDecimal net) {
    int sign = net.signum();
    if (sign < 0) {
      return "pay";
    }
    return sign > 0 ? "receive" : "none";
  }

  /** The line count is the longest aligned array (a partially-bound form is padded with blanks). */
  private static int lineCount(SplitForm form) {
    return List.of(
            size(form.categoryText()),
            size(form.lineCategoryId()),
            size(form.lineCategoryType()),
            size(form.lineAmount()),
            size(form.lineNote()))
        .stream()
        .max(Integer::compare)
        .orElse(0);
  }

  private static int size(List<String> list) {
    return list == null ? 0 : list.size();
  }

  private static String at(List<String> list, int index) {
    if (list == null || index >= list.size() || list.get(index) == null) {
      return "";
    }
    return list.get(index);
  }

  private static List<String> appended(List<String> list, String value) {
    List<String> copy = new ArrayList<>(list == null ? List.of() : list);
    copy.add(value);
    return copy;
  }

  private static List<String> removed(List<String> list, int index) {
    List<String> copy = new ArrayList<>(list == null ? List.of() : list);
    if (index >= 0 && index < copy.size()) {
      copy.remove(index);
    }
    return copy;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private SplitForm withLines(
      SplitForm form,
      List<String> categoryText,
      List<String> lineCategoryId,
      List<String> lineCategoryType,
      List<String> lineAmount,
      List<String> lineNote) {
    return new SplitForm(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.payeeText(),
        form.note(),
        form.total(),
        form.spendingCurrencyCode(),
        form.fundingTotal(),
        form.baseTotal(),
        categoryText,
        lineCategoryId,
        lineCategoryType,
        lineAmount,
        lineNote,
        form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }

  /**
   * The resolved currency state used to derive the panel's cross-currency readouts. For a
   * single-currency split the rates are zero and the derived helpers return {@code ""}.
   */
  private record CurrencyContext(
      boolean cross,
      String funding,
      String spending,
      String base,
      boolean neitherIsBase,
      BigDecimal fundingTotal,
      BigDecimal baseTotal,
      BigDecimal rateSpendingToFunding,
      BigDecimal rateSpendingToBase) {

    static CurrencyContext singleCurrency(String funding) {
      return new CurrencyContext(
          false,
          funding,
          funding,
          funding,
          false,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);
    }

    /** A derived amount as a German string, or {@code ""} when single-currency. */
    String derived(BigDecimal spendingMagnitude, BigDecimal rate) {
      if (!cross || spendingMagnitude.signum() == 0) {
        return "";
      }
      return MoneyFormat.number(derivedValue(spendingMagnitude, rate), FRACTION_DIGITS);
    }

    BigDecimal derivedValue(BigDecimal spendingMagnitude, BigDecimal rate) {
      return spendingMagnitude.multiply(rate).setScale(FRACTION_DIGITS, RoundingMode.HALF_UP);
    }

    BigDecimal fundingOf(BigDecimal spendingMagnitude) {
      return derivedValue(spendingMagnitude, rateSpendingToFunding);
    }

    /**
     * The amount that hits the funding account: converted when cross, the spending net otherwise.
     */
    BigDecimal fundingNet(BigDecimal spendingMagnitude) {
      return cross ? fundingOf(spendingMagnitude) : spendingMagnitude;
    }
  }
}
