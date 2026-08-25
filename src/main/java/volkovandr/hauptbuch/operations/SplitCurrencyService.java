package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The cross-currency header rule of a multi-line entry (register §3.8a/§3.10, issue receipts/23,
 * decision 4) — the <em>single</em> implementation both entry surfaces read. {@link
 * SplitPanelAssembler} builds the register's split panel from it and {@code ReceiptEditorAssembler}
 * the receipt post-process editor, so the two headers cannot drift: one receipt billed in one
 * currency and paid from an account in another behaves the same whichever screen it was entered on.
 *
 * <p>Public and in the module's root package on purpose: {@code receipts} needs the behaviour and
 * may only reach {@code operations}' public top-level types (CLAUDE.md §1.1).
 *
 * <p>Two jobs, both about the same header. {@link #resolve} works out the state the fragments
 * render — cross or not, which totals are needed, the two derived rates, the readouts. {@link
 * #proposeTotals} fills a <em>blank</em> total from the rate feed (via {@code ledger}'s {@link
 * CrossCurrencyFieldsService}, which owns every rate proposal): the funding total from the spending
 * total, then the base total from the funding one. A total the operator typed is never overwritten
 * — the proposal is a starting point, not an authority. Accepted consequence (decision 6): a
 * proposal can go stale if the number it came from is edited afterwards.
 */
@Service
public class SplitCurrencyService {

  /** Intermediate scale for the total ÷ total rate division, before amounts are rounded. */
  private static final int RATE_SCALE = 10;

  private final AccountService accountService;
  private final SettingsService settingsService;
  private final CrossCurrencyFieldsService crossCurrencyFieldsService;

  SplitCurrencyService(
      AccountService accountService,
      SettingsService settingsService,
      CrossCurrencyFieldsService crossCurrencyFieldsService) {
    this.accountService = accountService;
    this.settingsService = settingsService;
    this.crossCurrencyFieldsService = crossCurrencyFieldsService;
  }

  /**
   * Resolve the header state from the funding leg's currency and the spending selector. Lenient
   * throughout — a half-typed total simply reads as zero, so the header renders sensibly mid-entry.
   */
  public SplitCurrencyContext resolve(SplitCurrencyQuery query) {
    String funding = orEmpty(query.fundingCurrencyCode());
    String spending = blankToNull(query.spendingCurrencyCode());
    if (spending == null || funding.isBlank() || spending.equals(funding)) {
      return SplitCurrencyContext.singleCurrency(funding);
    }

    String base = settingsService.baseCurrency().orElse(funding);
    boolean neitherIsBase = !funding.equals(base) && !spending.equals(base);
    BigDecimal totalSpending = lenientParse(query.total());
    BigDecimal totalFunding = lenientParse(query.fundingTotal());
    BigDecimal totalBase =
        resolvedBaseTotal(funding, spending, base, totalSpending, totalFunding, query.baseTotal());
    return new SplitCurrencyContext(
        true,
        funding,
        spending,
        base,
        neitherIsBase,
        totalFunding,
        totalBase,
        ratio(totalFunding, totalSpending),
        ratio(totalBase, totalSpending));
  }

  /**
   * The base-currency total: when one leg already <em>is</em> the base currency that leg's own
   * total is it, and the separate base field never renders. Only when neither is does the operator
   * supply a third number.
   */
  private static BigDecimal resolvedBaseTotal(
      String funding,
      String spending,
      String base,
      BigDecimal totalSpending,
      BigDecimal totalFunding,
      String baseTotal) {
    if (funding.equals(base)) {
      return totalFunding;
    }
    if (spending.equals(base)) {
      return totalSpending;
    }
    return lenientParse(baseTotal);
  }

  /**
   * Fill whichever header total is still blank from the rate feed (decision 6): the funding total
   * from the spending total, then the base total from the funding one — so the operator confirms
   * numbers rather than computing them. Returns both totals unchanged for a single-currency entry,
   * an unknown account, or a funding leg with no account at all (a person funds it, which is
   * single-currency by construction).
   */
  public SplitTotals proposeTotals(SplitTotalsQuery query) {
    String spending = blankToNull(query.spendingCurrencyCode());
    String funding =
        query.accountId() == null
            ? null
            : accountService.findById(query.accountId()).map(Account::currencyCode).orElse(null);
    if (funding == null || spending == null || spending.equals(funding)) {
      return new SplitTotals(query.fundingTotal(), query.baseTotal());
    }

    String resolvedFunding =
        isBlank(query.fundingTotal())
            ? crossCurrencyFieldsService.prefillFundingTotal(
                funding, spending, query.date(), query.total())
            : query.fundingTotal();
    if (!isBlank(query.baseTotal())) {
      return new SplitTotals(resolvedFunding, query.baseTotal());
    }
    // The base proposal rides on ledger's existing prefillBase, reached through resolve() exactly
    // as the simple dock reaches it — so the number the split header proposes is the dock's number.
    CrossCurrencyFields fields =
        crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(
                funding, spending, query.date(), resolvedFunding, null, query.baseTotal()));
    boolean proposed = fields.neitherIsBase() && fields.baseAmountText() != null;
    return new SplitTotals(resolvedFunding, proposed ? fields.baseAmountText() : query.baseTotal());
  }

  private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
    if (denominator.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return numerator.divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal lenientParse(String text) {
    if (isBlank(text)) {
      return BigDecimal.ZERO;
    }
    try {
      return MoneyFormat.parse(text);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value;
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
