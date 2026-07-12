package volkovandr.hauptbuch.operations;

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
   * The amount-field layout for a submitted dock form (register §3.5/§3.8a): the funding account's
   * currency against the (possibly overridden) category-currency selection, with any already-typed
   * category/base amount preserved for redisplay.
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
                        form.categoryCurrencyCode(),
                        form.date(),
                        form.amount(),
                        form.categoryAmount(),
                        form.baseAmount())))
        .orElseGet(() -> CrossCurrencyFields.singleCurrency(""));
  }

  /**
   * The single-currency amount-field state for a known funding account (edit mode, register §3.1) —
   * cross-currency transactions are not yet dock-editable, so this is always single-currency.
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
