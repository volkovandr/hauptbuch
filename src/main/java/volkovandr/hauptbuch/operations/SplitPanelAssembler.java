package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountEntryLabel;
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
  private final SplitTagPills tagPills;
  private final TransactionCurrencyResolver transactionCurrencyResolver;

  SplitPanelAssembler(
      AccountService accountService,
      SettingsService settingsService,
      SplitTagPills tagPills,
      TransactionCurrencyResolver transactionCurrencyResolver) {
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.tagPills = tagPills;
    this.transactionCurrencyResolver = transactionCurrencyResolver;
  }

  /** Build the panel view model for the current form state, optionally carrying a message. */
  SplitPanel panel(SplitForm form, String error) {
    CurrencyContext ctx = resolveCurrencyContext(form);
    int count = SplitLineArrays.lineCount(form);
    Map<Long, String> labels = tagPills.labelsFor(form);
    List<SplitLineView> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < count; i++) {
      String amount = SplitLineArrays.at(form.lineAmount(), i);
      String type = SplitLineArrays.at(form.lineCategoryType(), i);
      String direction = SplitLineArrays.at(form.lineTransferDirection(), i);
      String personDirection = SplitLineArrays.at(form.linePersonDirection(), i);
      net = net.add(SplitLineAmounts.lenientContribution(amount, type, direction, personDirection));
      BigDecimal magnitude = lenientParse(amount).abs();
      lines.add(
          new SplitLineView(
              i,
              SplitLineArrays.at(form.categoryText(), i),
              SplitLineArrays.at(form.lineCategoryId(), i),
              type,
              direction,
              SplitLineArrays.at(form.linePersonName(), i),
              personDirection,
              SplitLineArrays.at(form.linePersonRevive(), i),
              amount,
              SplitLineArrays.at(form.lineNote(), i),
              ctx.derived(magnitude, ctx.rateSpendingToFunding()),
              ctx.derived(magnitude, ctx.rateSpendingToBase()),
              tagPills.pills(SplitLineArrays.tagsAt(form.lineTagIds(), i), labels)));
    }

    BigDecimal total = lenientParse(form.total());
    BigDecimal netMagnitude = net.abs();
    BigDecimal remaining = total.subtract(netMagnitude);
    return new SplitPanel(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.fundingPersonName(),
        form.fundingPersonDirection(),
        form.fundingPersonRevive(),
        accountEntryText(form),
        form.payeeText(),
        form.note(),
        MoneyFormat.number(total, FRACTION_DIGITS),
        currencyView(ctx, netMagnitude),
        lines,
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        remaining.signum() == 0,
        MoneyFormat.number(ctx.fundingNet(netMagnitude), FRACTION_DIGITS),
        direction(net),
        tagPills.pills(form.tagId(), labels),
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
    String funding = fundingCurrency(form);
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

  /**
   * The funding leg's currency (register §3.5/§3.10, issue 07): the account's own, or — when the
   * Account field named a person — the transaction currency, since a debt leaf has no currency of
   * its own until it is provisioned in one at commit. Mirrors {@link
   * DockAmountFieldsService#forForm}'s {@code fundingCurrency}, so the layout shown here always
   * agrees with what {@link DockSplitService} will actually book.
   */
  private String fundingCurrency(SplitForm form) {
    if (form.hasFundingPerson()) {
      String currency =
          transactionCurrencyResolver.forFundingPerson(
              form.fundingPersonName(), form.spendingCurrencyCode());
      return currency == null ? "" : currency;
    }
    if (form.accountId() == null) {
      return "";
    }
    return accountService.findById(form.accountId()).map(Account::currencyCode).orElse("");
  }

  /**
   * The value the panel's Account input shows (register §3.3/§3.10, issue 07): the {@code for}/
   * {@code by} sigil when the Account field resolved to a person, otherwise the ordinary account's
   * {@code Name (CUR)} label — mirrors {@code DockEditService#accountEntryText}.
   */
  private String accountEntryText(SplitForm form) {
    String sigil = form.fundingPersonSigil();
    if (sigil != null) {
      return sigil;
    }
    if (form.accountId() == null) {
      return null;
    }
    return accountService.findById(form.accountId()).map(AccountEntryLabel::format).orElse(null);
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
    return SplitLineArrays.appendedLine(form, rest);
  }

  /** Remove the line at {@code index} across every aligned array. Returns a new form. */
  SplitForm removeLine(SplitForm form, int index) {
    return SplitLineArrays.removedLine(form, index);
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
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
