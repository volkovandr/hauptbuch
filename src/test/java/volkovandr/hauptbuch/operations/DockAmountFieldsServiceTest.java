package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

  private final AccountService accountService = mock();
  private final CurrencyService currencyService = mock();
  private final CrossCurrencyFieldsService crossCurrencyFieldsService = mock();
  private final TransactionCurrencyResolver transactionCurrencyResolver = mock();
  private final DockAmountFieldsService service =
      new DockAmountFieldsService(
          accountService, currencyService, crossCurrencyFieldsService, transactionCurrencyResolver);

  private static Account account(long id, String currency) {
    return new Account(id, "n", "asset", null, currency, null, null, null, null, false, false);
  }

  private static DockEntryForm form(
      Long accountId,
      String fundingPersonName,
      String fundingPersonDirection,
      Long categoryId,
      String transferDirection,
      String categoryCurrencyCode) {
    return new DockEntryForm(
        null,
        DATE,
        accountId,
        fundingPersonName,
        fundingPersonDirection,
        null,
        null,
        "20",
        categoryId,
        categoryCurrencyCode,
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
        null);
  }

  private CrossCurrencyFieldsQuery captureQuery() {
    ArgumentCaptor<CrossCurrencyFieldsQuery> captor =
        ArgumentCaptor.forClass(CrossCurrencyFieldsQuery.class);
    org.mockito.Mockito.verify(crossCurrencyFieldsService).resolve(captor.capture());
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
}
