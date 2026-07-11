package volkovandr.hauptbuch.operations;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * The add-a-currency domain operation (plan stage 6d). Adding a currency is not one insert: the V2
 * seed fans each seeded currency out into a per-currency <em>system</em> leaf under the {@code
 * Opening Balances} parent, and a runtime add must do the same against an already-populated book,
 * or opening-balance entry in the new currency silently breaks (CLAUDE.md §1.7, §4).
 *
 * <p>So this composes {@code ledger}'s currency insert with {@code accounts}' leaf creation, in one
 * transaction: the {@code currency} row, then one {@code Opening Balances &lt;CODE&gt;} equity leaf
 * (data-model §3.2/T-DM-4), hung under the system parent the seed created once. {@code FX
 * gain/loss} is deliberately <em>not</em> provisioned — with the auto-booking retired (data-model
 * §6.3, 2026-07-11) it is no longer a system leaf but a plain category the user creates on demand.
 *
 * <p>It likewise does <em>not</em> back-fill a leaf under every category parent: per data-model
 * §6.5 a category's currency-leaf appears lazily — the first time you actually spend that currency,
 * via the stage-6b subdivision path — so eager back-fill would manufacture the empty per-currency
 * leaves §6.5 avoids.
 */
@Service
public class CurrencyProvisioningService {

  /** The system parent the seed creates once; each currency gets one leaf under it. */
  private static final String OPENING_BALANCES = "Opening Balances";

  private final CurrencyService currencyService;
  private final AccountService accountService;

  CurrencyProvisioningService(CurrencyService currencyService, AccountService accountService) {
    this.currencyService = currencyService;
    this.accountService = accountService;
  }

  /**
   * Add a currency and provision its system leaf. Atomic: either the currency and its Opening
   * Balances leaf land together, or nothing does.
   *
   * @param code ISO-4217 code (case-insensitive; stored upper-case)
   * @param minorUnits fractional digits (2 for EUR, 0 for JPY)
   * @param symbol display symbol, e.g. {@code €}; blank treated as none
   * @param name human-readable name, e.g. {@code Norwegian Krone}
   * @return the persisted currency
   * @throws IllegalArgumentException if the currency fields are invalid or the currency exists
   * @throws IllegalStateException if a system parent is missing (the seed did not run)
   */
  @Transactional
  public Currency createCurrency(String code, int minorUnits, String symbol, String name) {
    Currency currency = currencyService.insert(code, minorUnits, symbol, name);
    provisionSystemLeaf(OPENING_BALANCES, currency.code());
    return currency;
  }

  /** Hang one {@code &lt;parent&gt; &lt;CODE&gt;} leaf, inheriting the parent's type. */
  private void provisionSystemLeaf(String parentName, String currencyCode) {
    Account parent =
        accountService
            .findTopLevelByName(parentName)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "System parent '" + parentName + "' is missing — the V2 seed did not run"));
    accountService.insertLeaf(
        parentName + " " + currencyCode, parent.type(), parent.accountId(), currencyCode);
  }
}
