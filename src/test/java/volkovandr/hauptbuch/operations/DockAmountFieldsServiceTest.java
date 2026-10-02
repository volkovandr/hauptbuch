package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsQuery;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.ledger.CurrencyService;
import volkovandr.hauptbuch.ledger.Payee;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.operations.repository.GhostSuggestionRepository;

/**
 * Unit tier (plan §1.5): which currencies the dock's amount-field layout is computed against
 * (register §3.5/§3.8a, plan stage 8b.1). What matters is that the layout the user is
 * <em>shown</em> is derived from the same currencies {@link DockCommitService} will actually
 * <em>book</em> — a disagreement means the dock hides a field the commit then demands.
 */
class DockAmountFieldsServiceTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final LocalDate DATE = LocalDate.of(2026, 2, 1);
  private static final long CASH_ID = 1L;
  private static final long VISA_ID = 2L;
  private static final long PAYEE_ID = 7L;
  private static final String SHOP = "ShopAaa";
  private static final String USD = "USD";
  private static final CrossCurrencyFields EUR_TO_CHF =
      new CrossCurrencyFields(EUR, CHF, true, false, null, null);
  private static final CrossCurrencyFields CHF_TO_USD =
      new CrossCurrencyFields(CHF, USD, true, true, null, null);

  private final AccountService accountService = mock();
  private final CurrencyService currencyService = mock();
  private final CrossCurrencyFieldsService crossCurrencyFieldsService = mock();
  private final TransactionCurrencyResolver transactionCurrencyResolver = mock();
  private final PayeeService payeeService = mock();
  private final GhostSuggestionRepository ghostSuggestionRepository = mock();
  private final DockAmountFieldsService service =
      new DockAmountFieldsService(
          accountService,
          currencyService,
          crossCurrencyFieldsService,
          transactionCurrencyResolver,
          payeeService,
          ghostSuggestionRepository);

  private static Account account(long id, String currency) {
    return new Account(
        id, "n", "asset", null, currency, null, null, null, null, false, false, false);
  }

  private static DockEntryForm form(
      Long accountId,
      String fundingPersonName,
      String fundingPersonDirection,
      Long categoryId,
      String transferDirection,
      String categoryCurrencyCode) {
    return form(
        accountId,
        fundingPersonName,
        fundingPersonDirection,
        categoryId,
        transferDirection,
        categoryCurrencyCode,
        null);
  }

  private static DockEntryForm form(
      Long accountId,
      String fundingPersonName,
      String fundingPersonDirection,
      Long categoryId,
      String transferDirection,
      String categoryCurrencyCode,
      String payeeText) {
    return new DockEntryForm(
        null,
        DATE,
        accountId,
        fundingPersonName,
        fundingPersonDirection,
        null,
        payeeText,
        "20",
        categoryId,
        categoryCurrencyCode,
        null,
        null,
        null,
        null,
        null,
        transferDirection,
        null,
        null,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        false);
  }

  private CrossCurrencyFieldsQuery captureQuery() {
    ArgumentCaptor<CrossCurrencyFieldsQuery> captor =
        ArgumentCaptor.forClass(CrossCurrencyFieldsQuery.class);
    verify(crossCurrencyFieldsService).resolve(captor.capture());
    return captor.getValue();
  }

  @Test
  void ordinaryAccountUsesItsOwnCurrencyAgainstTheSelector() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, EUR)));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    service.forForm(form(CASH_ID, null, null, 9L, null, CHF));

    CrossCurrencyFieldsQuery query = captureQuery();
    assertThat(query.fundingCurrencyCode()).isEqualTo(EUR);
    assertThat(query.categoryCurrencyCode()).isEqualTo(CHF);
  }

  @Test
  void personFundingLegUsesTheTransactionCurrency() {
    // A person's leaf has no currency of its own until commit provisions it in one.
    when(transactionCurrencyResolver.forFundingPerson("Max", null)).thenReturn(EUR);
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    service.forForm(form(null, "Max", "BY", 9L, null, null));

    assertThat(captureQuery().fundingCurrencyCode()).isEqualTo(EUR);
  }

  @Test
  void personFundingRealAccountCounterpartStaysCrossCurrencyCapable() {
    // The regression this test exists for: a person paying INTO a real account is legitimately
    // cross-currency (register §3.5 — the selector sets only the legs that are not real accounts),
    // so the counterpart-amount field must still be revealed. Short-circuiting every person-funded
    // entry to single-currency hid a field the commit then demanded ("A CHF amount is required").
    when(transactionCurrencyResolver.forFundingPerson("Max", null)).thenReturn(EUR);
    when(accountService.findById(VISA_ID)).thenReturn(Optional.of(account(VISA_ID, CHF)));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    service.forForm(form(null, "Max", "BY", VISA_ID, "TO", null));

    CrossCurrencyFieldsQuery query = captureQuery();
    assertThat(query.fundingCurrencyCode()).isEqualTo(EUR);
    assertThat(query.categoryCurrencyCode()).isEqualTo(CHF);
  }

  @Test
  void noFundingLegAtAllCollapsesToEmpty() {
    assertThat(service.forForm(form(null, null, null, 9L, null, null)).fundingCurrencyCode())
        .isEmpty();
  }

  @Test
  void personWithNoResolvableCurrencyCollapsesToEmpty() {
    // No override, no existing debt, no base currency — nothing to render a layout against.
    when(transactionCurrencyResolver.forFundingPerson("Max", null)).thenReturn(null);

    assertThat(service.forForm(form(null, "Max", "BY", 9L, null, null)).fundingCurrencyCode())
        .isEmpty();
  }

  @Test
  void blankSelectorPreselectsTheCurrencyLastUsedWithThePayeeOnTheAccount() {
    // The Account/Payee refresh posts without the selector (issue transaction-register-ui/17).
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, EUR)));
    when(payeeService.findExisting(SHOP))
        .thenReturn(Optional.of(new Payee(PAYEE_ID, SHOP, null, null, null)));
    when(ghostSuggestionRepository.suggestCurrencyFor(PAYEE_ID, CASH_ID))
        .thenReturn(Optional.of(CHF));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    service.forForm(form(CASH_ID, null, null, null, null, null, SHOP));

    assertThat(captureQuery().categoryCurrencyCode()).isEqualTo(CHF);
  }

  @Test
  void blankSelectorWithoutPayeeHistoryFallsBackToTheAccountCurrency() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, EUR)));
    when(payeeService.findExisting(SHOP)).thenReturn(Optional.empty());
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    service.forForm(form(CASH_ID, null, null, null, null, null, SHOP));

    // No override: the layout resolves against the funding account's own currency.
    assertThat(captureQuery().categoryCurrencyCode()).isNull();
  }

  @Test
  void explicitSelectorWinsOverThePayeeSuggestion() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, EUR)));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(CrossCurrencyFields.singleCurrency(EUR));

    service.forForm(form(CASH_ID, null, null, null, null, EUR, SHOP));

    assertThat(captureQuery().categoryCurrencyCode()).isEqualTo(EUR);
    verify(ghostSuggestionRepository, never()).suggestCurrencyFor(PAYEE_ID, CASH_ID);
  }

  // ── entryFrom: the dock's fields mapped onto the legs (issue transaction-register-ui/04) ──

  private static DockEntryForm amountsForm(
      String categoryCurrencyCode, String amount, String offAccountAmount) {
    return suggestionForm(categoryCurrencyCode, amount, offAccountAmount, null, null, null);
  }

  private static DockEntryForm suggestionForm(
      String categoryCurrencyCode,
      String amount,
      String offAccountAmount,
      String baseAmount,
      String offAccountSuggestion,
      String baseSuggestion) {
    return new DockEntryForm(
        null,
        DATE,
        CASH_ID,
        null,
        null,
        null,
        null,
        amount,
        9L,
        categoryCurrencyCode,
        offAccountAmount,
        baseAmount,
        offAccountSuggestion,
        baseSuggestion,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        false);
  }

  private void layoutIs(CrossCurrencyFields fields) {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, EUR)));
    when(crossCurrencyFieldsService.resolve(any())).thenReturn(fields);
  }

  @Test
  void singleCurrencyEntryCommitsTheAmountAsTheFundingLeg() {
    layoutIs(CrossCurrencyFields.singleCurrency(EUR));

    DockEntry entry = service.entryFrom(amountsForm(EUR, "−20", null));

    assertThat(entry.amount()).isEqualTo("−20");
    assertThat(entry.categoryAmount()).isNull();
  }

  @Test
  void crossCurrencyEntryCommitsOffAccountAsTheFundingLegAndAmountAsTheCounterpart() {
    layoutIs(new CrossCurrencyFields(EUR, CHF, true, false, null, null));

    DockEntry entry = service.entryFrom(amountsForm(CHF, "−10", "9,10"));

    // The sign typed on the Amount belongs to the funding leg (register §3.8).
    assertThat(entry.amount()).isEqualTo("−9,10");
    assertThat(entry.categoryAmount()).isEqualTo("10");
    assertThat(entry.categoryCurrencyCode()).isEqualTo(CHF);
  }

  @Test
  void crossCurrencyEntryWithoutOffAccountIsRefusedNamingTheField() {
    layoutIs(new CrossCurrencyFields(EUR, CHF, true, false, null, null));

    assertThatThrownBy(() -> service.entryFrom(amountsForm(CHF, "10", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("An Off account (EUR) amount is required");
  }

  // ── amountsFor: Off account / Base proposed from the Amount (issue transaction-register-ui/27)
  // ──

  @Test
  void proposesOffAccountFromTheAmountWhileTheFieldIsBlank() {
    layoutIs(EUR_TO_CHF);
    when(crossCurrencyFieldsService.prefillFundingTotal(EUR, CHF, DATE, "10")).thenReturn("9,50");

    DockAmounts amounts = service.amountsFor(suggestionForm(CHF, "10", "", null, "", null));

    assertThat(amounts.offAccountText()).isEqualTo("9,50");
    assertThat(amounts.offAccountSuggestion()).isEqualTo("9,50");
  }

  @Test
  void reproposesOffAccountWhileItStillHoldsTheLastSuggestion() {
    layoutIs(EUR_TO_CHF);
    when(crossCurrencyFieldsService.prefillFundingTotal(EUR, CHF, DATE, "20")).thenReturn("19,00");

    // The Amount changed from 10 to 20; Off account still shows the untouched proposal.
    DockAmounts amounts = service.amountsFor(suggestionForm(CHF, "20", "9,50", null, "9,50", null));

    assertThat(amounts.offAccountText()).isEqualTo("19,00");
  }

  @Test
  void keepsOffAccountTheOperatorTyped() {
    layoutIs(EUR_TO_CHF);

    DockAmounts amounts = service.amountsFor(suggestionForm(CHF, "20", "9,00", null, "9,50", null));

    assertThat(amounts.offAccountText()).isEqualTo("9,00");
    assertThat(amounts.offAccountSuggestion()).isNull();
    verify(crossCurrencyFieldsService, never()).prefillFundingTotal(EUR, CHF, DATE, "20");
  }

  @Test
  void leavesOffAccountBlankWithoutRate() {
    layoutIs(EUR_TO_CHF);
    when(crossCurrencyFieldsService.prefillFundingTotal(EUR, CHF, DATE, "10")).thenReturn(null);

    DockAmounts amounts = service.amountsFor(suggestionForm(CHF, "10", null, null, null, null));

    assertThat(amounts.offAccountText()).isNull();
  }

  @Test
  void proposesBaseFromTheAmountWhenNeitherCurrencyIsBase() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, CHF)));
    when(crossCurrencyFieldsService.resolve(any())).thenReturn(CHF_TO_USD);
    when(crossCurrencyFieldsService.proposeBase(USD, DATE, "10")).thenReturn("9,00");

    DockAmounts amounts = service.amountsFor(suggestionForm(USD, "10", "9,47", null, null, null));

    assertThat(amounts.fields().baseAmountText()).isEqualTo("9,00");
    assertThat(amounts.baseSuggestion()).isEqualTo("9,00");
    // Off account was typed, so it is kept — and Base does not follow it.
    assertThat(amounts.offAccountText()).isEqualTo("9,47");
  }

  @Test
  void keepsBaseTheOperatorTyped() {
    when(accountService.findById(CASH_ID)).thenReturn(Optional.of(account(CASH_ID, CHF)));
    when(crossCurrencyFieldsService.resolve(any())).thenReturn(CHF_TO_USD);

    DockAmounts amounts =
        service.amountsFor(suggestionForm(USD, "10", "9,47", "8,80", null, "9,00"));

    assertThat(amounts.fields().baseAmountText()).isEqualTo("8,80");
    assertThat(amounts.baseSuggestion()).isNull();
  }

  @Test
  void singleCurrencyProposesNothing() {
    layoutIs(CrossCurrencyFields.singleCurrency(EUR));

    DockAmounts amounts = service.amountsFor(suggestionForm(EUR, "10", null, null, null, null));

    assertThat(amounts.offAccountText()).isNull();
    assertThat(amounts.offAccountSuggestion()).isNull();
    assertThat(amounts.baseSuggestion()).isNull();
  }
}
