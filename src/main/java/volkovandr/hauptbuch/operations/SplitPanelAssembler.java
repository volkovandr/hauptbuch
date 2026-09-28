package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
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
 * agrees to the minor unit once the lines balance. That whole rule lives in {@link
 * SplitCurrencyService} (issue receipts/23, decision 4), which the receipt post-process editor
 * reads too, so the two surfaces' headers cannot drift apart.
 *
 * <p>Public because the recurring template editor is the split panel in template mode (data-model
 * §14.1) and assembles its panel here too.
 */
@Component
public class SplitPanelAssembler {

  /** German entry is to the minor unit; two places covers EUR/CHF/USD. */
  private static final int FRACTION_DIGITS = 2;

  private final AccountService accountService;
  private final SplitTagPills tagPills;
  private final TransactionCurrencyResolver transactionCurrencyResolver;
  private final SplitCurrencyService splitCurrencyService;

  SplitPanelAssembler(
      AccountService accountService,
      SplitTagPills tagPills,
      TransactionCurrencyResolver transactionCurrencyResolver,
      SplitCurrencyService splitCurrencyService) {
    this.accountService = accountService;
    this.tagPills = tagPills;
    this.transactionCurrencyResolver = transactionCurrencyResolver;
    this.splitCurrencyService = splitCurrencyService;
  }

  /** Build the panel view model for the current form state, optionally carrying a message. */
  public SplitPanel panel(SplitForm form, String error) {
    SplitCurrencyContext ctx =
        splitCurrencyService.resolve(
            new SplitCurrencyQuery(
                fundingCurrency(form),
                form.spendingCurrencyCode(),
                form.total(),
                form.fundingTotal(),
                form.baseTotal()));
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
              ctx.derivedFunding(magnitude),
              ctx.derivedBase(magnitude),
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
        ctx.view(netMagnitude),
        lines,
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        remaining.signum() == 0,
        MoneyFormat.number(ctx.fundingNet(netMagnitude), FRACTION_DIGITS),
        direction(net),
        tagPills.pills(form.tagId(), labels),
        error);
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
   * picker label — mirrors {@code DockEditService#accountEntryText}.
   */
  private String accountEntryText(SplitForm form) {
    String sigil = form.fundingPersonSigil();
    if (sigil != null) {
      return sigil;
    }
    if (form.accountId() == null) {
      return null;
    }
    return accountService
        .findById(form.accountId())
        .map(accountService::ownAccountEntryLabel)
        .orElse(null);
  }

  /**
   * Append a blank line whose amount defaults to "the rest" — {@code total − allocated} in the
   * spending currency (register §3.10) — so the last line closes the gap. Returns a new form; the
   * caller re-renders it.
   */
  public SplitForm addLine(SplitForm form) {
    SplitPanel current = panel(form, null);
    BigDecimal remaining = lenientParse(current.remaining());
    String rest = remaining.signum() > 0 ? current.remaining() : "";
    return SplitLineArrays.appendedLine(form, rest);
  }

  /**
   * Give the first line with no amount "the rest" (register §3.10), exactly as {@link #addLine}
   * gives a new line. A panel that opens blank — the recurring template editor — has no dock line
   * to seed from, so the total typed first flows into the first line here, as the register's Split
   * carries the dock's amount into it. A form whose lines all have amounts is returned unchanged.
   */
  public SplitForm restIntoBlankLine(SplitForm form) {
    List<String> amounts = form.lineAmount();
    if (amounts == null) {
      return form;
    }
    for (int i = 0; i < amounts.size(); i++) {
      if (amounts.get(i) == null || amounts.get(i).isBlank()) {
        SplitPanel current = panel(form, null);
        return lenientParse(current.remaining()).signum() > 0
            ? SplitLineArrays.withLineAmount(form, i, current.remaining())
            : form;
      }
    }
    return form;
  }

  /** Remove the line at {@code index} across every aligned array. Returns a new form. */
  public SplitForm removeLine(SplitForm form, int index) {
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
}
