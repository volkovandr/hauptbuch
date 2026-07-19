package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;
import volkovandr.hauptbuch.ledger.RegisterView;

/**
 * The dock's amount-field state, for both its controllers (register §3.5/§3.8a, plan stage 7d.1):
 * {@link RegisterEntryController} (the simple dock) and {@link RegisterSplitController} (the split
 * panel's Cancel back into the dock, and its own commit-success reset) both render the {@code dock}
 * fragment, so both need this — kept in one collaborator rather than each wiring {@link
 * AccountService}, {@link CurrencyService}, and {@link CrossCurrencyFieldsService} separately.
 */
@Service
class DockAmountFieldsService {

  private final AccountService accountService;
  private final CurrencyService currencyService;
  private final CrossCurrencyFieldsService crossCurrencyFieldsService;

  DockAmountFieldsService(
      AccountService accountService,
      CurrencyService currencyService,
      CrossCurrencyFieldsService crossCurrencyFieldsService) {
    this.accountService = accountService;
    this.currencyService = currencyService;
    this.crossCurrencyFieldsService = crossCurrencyFieldsService;
  }

  /** Every currency the book knows, for the category-currency picker's options. */
  List<Currency> currencies() {
    return currencyService.findAll();
  }

  /**
   * The split header's base total (register §3.8a, plan stage 7d.2): when the split is
   * cross-currency with neither leg the base currency and the base field is still blank, pre-fill
   * it from the carry-forward rate (confirmable); otherwise return the entered value unchanged.
   * Reuses the same {@link CrossCurrencyFieldsService} as the simple dock, so the proposal is
   * identical.
   */
  String splitBaseTotalPrefill(
      Long accountId,
      String spendingCurrencyCode,
      LocalDate date,
      String fundingTotal,
      String baseTotal) {
    if (accountId == null || spendingCurrencyCode == null || spendingCurrencyCode.isBlank()) {
      return baseTotal;
    }
    String fundingCurrency =
        accountService.findById(accountId).map(Account::currencyCode).orElse(null);
    if (fundingCurrency == null || spendingCurrencyCode.equals(fundingCurrency)) {
      return baseTotal;
    }
    CrossCurrencyFields fields =
        crossCurrencyFieldsService.resolve(
            new CrossCurrencyFieldsQuery(
                fundingCurrency, spendingCurrencyCode, date, fundingTotal, null, baseTotal));
    if (!fields.neitherIsBase() || fields.baseAmountText() == null) {
      return baseTotal;
    }
    return fields.baseAmountText();
  }

  /**
   * The amount-field layout for a submitted dock form (register §3.5/§3.8a): the funding account's
   * currency against the counterpart currency, with any already-typed category/base amount
   * preserved for redisplay. The counterpart currency is the category-currency selection for a
   * category entry, or — for a transfer (register §3.8, plan stage 7d.3) — the resolved counterpart
   * account's own currency (fixed by the account, not the selector), so a cross-currency transfer
   * reveals the same counterpart-amount field a cross-currency category entry does.
   */
  CrossCurrencyFields forForm(DockEntryForm form) {
    if (form.accountId() == null) {
      return CrossCurrencyFields.singleCurrency("");
    }
    return accountService
        .findById(form.accountId())
        .map(
            a ->
                crossCurrencyFieldsService.resolve(
                    new CrossCurrencyFieldsQuery(
                        a.currencyCode(),
                        counterpartCurrency(form),
                        form.date(),
                        form.amount(),
                        form.categoryAmount(),
                        form.baseAmount())))
        .orElseGet(() -> CrossCurrencyFields.singleCurrency(""));
  }

  /**
   * The counterpart leg's currency for the field layout: a transfer's counterpart account fixes it
   * (register §3.8, plan stage 7d.3), so it is looked up from the resolved account id; otherwise it
   * is the category-currency selector's value (null/blank = the funding account's, no override).
   */
  private String counterpartCurrency(DockEntryForm form) {
    if (form.transferDirection() != null
        && !form.transferDirection().isBlank()
        && form.categoryId() != null) {
      return accountService
          .findById(form.categoryId())
          .map(Account::currencyCode)
          .orElse(form.categoryCurrencyCode());
    }
    return form.categoryCurrencyCode();
  }

  /**
   * The amount-field layout for a transaction re-opened in edit mode (register §3.1/§3.8a, plan
   * stage 7f): the funding account's currency against the counterpart leg's, revealing the same
   * category/base amount fields the entry made. The amounts come from the loaded legs, so a
   * cross-currency transaction re-opens showing what was actually booked — in particular the
   * <em>frozen</em> base amount is redisplayed, never re-derived from today's rate feed (data-model
   * §6.4). A single-currency transaction carries no override and collapses to {@link
   * CrossCurrencyFields#singleCurrency}, the ≥95% path.
   */
  CrossCurrencyFields forEdit(DockEditModel edit) {
    if (edit.accountId() == null) {
      return CrossCurrencyFields.singleCurrency("");
    }
    return accountService
        .findById(edit.accountId())
        .map(
            a ->
                crossCurrencyFieldsService.resolve(
                    new CrossCurrencyFieldsQuery(
                        a.currencyCode(),
                        edit.categoryCurrencyCode(),
                        edit.date(),
                        edit.amount(),
                        edit.categoryAmount(),
                        edit.baseAmount())))
        .orElseGet(() -> CrossCurrencyFields.singleCurrency(""));
  }

  /**
   * The single-currency amount-field state for a known funding account: the split panel's Cancel
   * back into the dock, which drops the lines the dock cannot represent and so never carries an
   * override (register §3.9).
   */
  CrossCurrencyFields forAccount(Long accountId) {
    if (accountId == null) {
      return CrossCurrencyFields.singleCurrency("");
    }
    return accountService
        .findById(accountId)
        .map(Account::currencyCode)
        .map(CrossCurrencyFields::singleCurrency)
        .orElseGet(() -> CrossCurrencyFields.singleCurrency(""));
  }

  /**
   * The dock's fresh new-mode amount-field state: no override yet, so single-currency in the
   * funding account the dock implicitly pre-selects — the first of the viewed own accounts,
   * mirroring the account {@code <select>}'s own no-explicit-selection default.
   */
  CrossCurrencyFields fresh(RegisterView register) {
    return register.accounts().stream()
        .findFirst()
        .map(a -> CrossCurrencyFields.singleCurrency(a.currencyCode()))
        .orElseGet(() -> CrossCurrencyFields.singleCurrency(""));
  }
}
