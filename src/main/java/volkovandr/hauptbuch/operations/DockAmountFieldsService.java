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
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.RegisterView;
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

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
  private final TransactionCurrencyResolver transactionCurrencyResolver;
  private final PayeeService payeeService;
  private final GhostSuggestionRepository ghostSuggestionRepository;

  DockAmountFieldsService(
      AccountService accountService,
      CurrencyService currencyService,
      CrossCurrencyFieldsService crossCurrencyFieldsService,
      TransactionCurrencyResolver transactionCurrencyResolver,
      PayeeService payeeService,
      GhostSuggestionRepository ghostSuggestionRepository) {
    this.accountService = accountService;
    this.currencyService = currencyService;
    this.crossCurrencyFieldsService = crossCurrencyFieldsService;
    this.transactionCurrencyResolver = transactionCurrencyResolver;
    this.payeeService = payeeService;
    this.ghostSuggestionRepository = ghostSuggestionRepository;
  }

  /** Every currency the book knows, for the category-currency picker's options. */
  List<Currency> currencies() {
    return currencyService.findAll();
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
    String fundingCurrency = fundingCurrency(form);
    if (fundingCurrency == null) {
      return CrossCurrencyFields.singleCurrency("");
    }
    return crossCurrencyFieldsService.resolve(
        new CrossCurrencyFieldsQuery(
            fundingCurrency,
            counterpartCurrency(form),
            form.date(),
            null,
            DockAmountTexts.counterpartText(form.amount()),
            form.baseAmount()));
  }

  /**
   * The amount fields to redisplay for a submitted dock form, with the rate-feed proposals filled
   * in (issue transaction-register-ui/27). Both proposals derive from the {@code Amount} in the
   * transaction currency, as of the transaction date: {@code Off account} converts it into the
   * funding account's currency (through base), {@code Base} into the base currency. A field is
   * (re-)proposed only while it is blank or still holds the previous proposal; a value the operator
   * typed is kept. With no rate on file the proposal is blank, never a guess.
   */
  DockAmounts amountsFor(DockEntryForm form) {
    CrossCurrencyFields layout = forForm(form);
    if (!layout.crossCurrency()) {
      return new DockAmounts(layout, null, null, null);
    }
    String transactionCurrency = layout.categoryCurrencyCode();
    boolean proposeOffAccount = isSuggestion(form.offAccountAmount(), form.offAccountSuggestion());
    String offAccountSuggestion =
        proposeOffAccount
            ? crossCurrencyFieldsService.prefillFundingTotal(
                layout.fundingCurrencyCode(), transactionCurrency, form.date(), form.amount())
            : null;
    String offAccountText = proposeOffAccount ? offAccountSuggestion : form.offAccountAmount();
    String baseSuggestion = null;
    String baseText = null;
    if (layout.neitherIsBase()) {
      boolean proposeBase = isSuggestion(form.baseAmount(), form.baseSuggestion());
      baseSuggestion =
          proposeBase
              ? crossCurrencyFieldsService.proposeBase(
                  transactionCurrency, form.date(), form.amount())
              : null;
      baseText = proposeBase ? baseSuggestion : form.baseAmount();
    }
    return new DockAmounts(
        new CrossCurrencyFields(
            layout.fundingCurrencyCode(),
            transactionCurrency,
            true,
            layout.neitherIsBase(),
            layout.categoryAmountText(),
            baseText),
        offAccountText,
        offAccountSuggestion,
        baseSuggestion);
  }

  /** Whether a field still holds a proposal the server may replace: blank, or the last one. */
  private static boolean isSuggestion(String current, String lastSuggestion) {
    String value = current == null ? "" : current.strip();
    return value.isEmpty() || value.equals(lastSuggestion == null ? "" : lastSuggestion.strip());
  }

  /**
   * The {@link DockEntry} a submitted dock form commits (issue transaction-register-ui/04): the
   * form's fields mapped onto the two legs. A single-currency entry's {@code Amount} is the funding
   * leg's; a cross-currency entry's {@code Amount} is the counterpart's and its {@code Off account}
   * the funding leg's, the explicit sign moving with it ({@link DockAmountTexts}). Whether the
   * entry is cross-currency is decided exactly as the field layout the operator saw was ({@link
   * #forForm}), so the mapping always matches the fields on screen.
   *
   * @throws IllegalArgumentException if a cross-currency entry's {@code Off account} is blank
   */
  DockEntry entryFrom(DockEntryForm form) {
    String fundingAmount = form.amount();
    String categoryAmount = null;
    CrossCurrencyFields layout = forForm(form);
    if (layout.crossCurrency()) {
      if (form.offAccountAmount() == null || form.offAccountAmount().isBlank()) {
        throw new IllegalArgumentException(
            "An Off account (" + layout.fundingCurrencyCode() + ") amount is required");
      }
      fundingAmount = DockAmountTexts.fundingText(form.amount(), form.offAccountAmount());
      categoryAmount = DockAmountTexts.counterpartText(form.amount());
    }
    return new DockEntry(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.fundingPersonName(),
        form.fundingPersonDirection(),
        form.fundingPersonRevive(),
        null,
        blankToNull(form.payeeText()),
        form.categoryId() == null ? 0L : form.categoryId(),
        form.categoryCurrencyCode(),
        fundingAmount,
        categoryAmount,
        form.baseAmount(),
        form.note(),
        form.transferDirection(),
        form.personName(),
        form.personDirection(),
        form.personRevive(),
        form.tagId());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /**
   * The funding leg's currency: the account's own, or — when the Account field named a person
   * (register §3.3, plan stage 8b.1) — the transaction currency, since a debt leaf has no currency
   * of its own until it is provisioned in one at commit.
   *
   * <p>A person funding leg does <em>not</em> force the transaction single-currency. It does so
   * against a category or another person, because the currency selector sets every non-account leg
   * and they therefore all agree — but a person paying <em>into a real account</em> (a transfer
   * counterpart) is legitimately cross-currency, and must reveal the counterpart-amount field like
   * any other cross-currency entry. Deciding it here rather than short-circuiting keeps the field
   * layout the user sees identical to what {@link DockCommitService} will actually book.
   */
  private String fundingCurrency(DockEntryForm form) {
    if (form.hasFundingPerson()) {
      return transactionCurrencyResolver.forFundingPerson(
          form.fundingPersonName(), form.categoryCurrencyCode());
    }
    if (form.accountId() == null) {
      return null;
    }
    return accountService.findById(form.accountId()).map(Account::currencyCode).orElse(null);
  }

  /**
   * The counterpart leg's currency for the field layout: a transfer's counterpart account fixes it
   * (register §3.8, plan stage 7d.3), so it is looked up from the resolved account id; otherwise it
   * is the category-currency selector's value. A blank selector means the form was posted without
   * it — the Account or Payee field changed, so the currency default is re-derived: the currency
   * last used with this payee on this account (issue transaction-register-ui/17), else the funding
   * account's own (null, no override).
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
    if (form.categoryCurrencyCode() == null || form.categoryCurrencyCode().isBlank()) {
      return suggestedCurrency(form);
    }
    return form.categoryCurrencyCode();
  }

  /**
   * The currency last used with the form's payee on its funding account (issue
   * transaction-register-ui/17), or null when there is no funding account, the payee is new, or the
   * pair has no history — the caller then falls back to the funding account's currency.
   */
  private String suggestedCurrency(DockEntryForm form) {
    if (form.accountId() == null) {
      return null;
    }
    return payeeService
        .findExisting(form.payeeText())
        .map(Payee::payeeId)
        .flatMap(payeeId -> ghostSuggestionRepository.suggestCurrencyFor(payeeId, form.accountId()))
        .orElse(null);
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
