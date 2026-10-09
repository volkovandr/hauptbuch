package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.CrossCurrencyFields;
import volkovandr.hauptbuch.ledger.CrossCurrencyFieldsService;
import volkovandr.hauptbuch.statements.StatementCrossCurrencyService.CrossCurrencyView;

/**
 * Unit tier: what the statement dock asks for when a missing line is a transfer into another
 * currency (issue statements/09) — the decision of when to ask and what to propose, with the rate
 * lookups mocked.
 */
@ExtendWith(MockitoExtension.class)
class StatementCrossCurrencyServiceTest {

  private static final long STATEMENT = 4L;
  private static final long LINE_ID = 10L;
  private static final long OWN_ACCOUNT = 7L;
  private static final long TARGET = 9L;
  private static final LocalDate DATE = LocalDate.of(2026, 5, 2);

  @Mock private StatementService statementService;
  @Mock private StatementMatchService matchService;
  @Mock private AccountService accountService;
  @Mock private CrossCurrencyFieldsService crossCurrencyFieldsService;

  private StatementCrossCurrencyService service;

  @BeforeEach
  void setUp() {
    service =
        new StatementCrossCurrencyService(
            statementService, matchService, accountService, crossCurrencyFieldsService);
  }

  private static Account account(long id, String currency) {
    return new Account(
        id, "Acc" + id, "asset", null, currency, null, null, null, null, false, false, false);
  }

  private void statementOnEurAccount() {
    when(statementService.get(STATEMENT))
        .thenReturn(
            new Statement(
                STATEMENT,
                1L,
                OWN_ACCOUNT,
                Statement.STATE_NEW,
                "2026-05.csv",
                "path",
                null,
                null,
                null,
                null,
                null,
                null));
    when(accountService.findById(OWN_ACCOUNT)).thenReturn(Optional.of(account(OWN_ACCOUNT, "EUR")));
  }

  private void bankLineOf(String amount) {
    StatementLine line =
        new StatementLine(
            LINE_ID, 0, DATE, null, new BigDecimal(amount), "ShopAaa", "Card", null, "raw", null);
    when(matchService.lineOf(STATEMENT, LINE_ID))
        .thenReturn(new LineReview(line, LineStatus.MISSING, null, List.of()));
  }

  @Test
  void transferIntoAnotherCurrencyProposesTheCounterpartAmountFromTheBanksAmount() {
    statementOnEurAccount();
    bankLineOf("-12.50");
    when(accountService.findById(TARGET)).thenReturn(Optional.of(account(TARGET, "USD")));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(new CrossCurrencyFields("EUR", "USD", true, false, null, null));
    when(crossCurrencyFieldsService.prefillFundingTotal("USD", "EUR", DATE, "12,50"))
        .thenReturn("13,89");

    Optional<CrossCurrencyView> view = service.forTransfer(STATEMENT, LINE_ID, TARGET, "TO", DATE);

    assertThat(view).contains(new CrossCurrencyView("USD", "13,89", false, null));
  }

  @Test
  void neitherAccountInTheBaseCurrencyAlsoAsksForTheBaseAmount() {
    statementOnEurAccount();
    bankLineOf("-12.50");
    when(accountService.findById(TARGET)).thenReturn(Optional.of(account(TARGET, "USD")));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(new CrossCurrencyFields("EUR", "USD", true, true, null, "11,00"));

    Optional<CrossCurrencyView> view = service.forTransfer(STATEMENT, LINE_ID, TARGET, "TO", DATE);

    assertThat(view.orElseThrow().showBase()).isTrue();
    assertThat(view.orElseThrow().baseAmount()).isEqualTo("11,00");
  }

  @Test
  void noRateOnFileLeavesTheProposalBlank() {
    statementOnEurAccount();
    bankLineOf("-12.50");
    when(accountService.findById(TARGET)).thenReturn(Optional.of(account(TARGET, "USD")));
    when(crossCurrencyFieldsService.resolve(any()))
        .thenReturn(new CrossCurrencyFields("EUR", "USD", true, false, null, null));

    Optional<CrossCurrencyView> view = service.forTransfer(STATEMENT, LINE_ID, TARGET, "TO", DATE);

    assertThat(view.orElseThrow().counterpartAmount()).isNull();
  }

  @Test
  void transferInTheSameCurrencyAsksForNothing() {
    statementOnEurAccount();
    when(accountService.findById(TARGET)).thenReturn(Optional.of(account(TARGET, "EUR")));

    assertThat(service.forTransfer(STATEMENT, LINE_ID, TARGET, "TO", DATE)).isEmpty();
  }

  @Test
  void noTransferTargetAsksForNothing() {
    assertThat(service.forTransfer(STATEMENT, LINE_ID, null, "TO", DATE)).isEmpty();
    assertThat(service.forTransfer(STATEMENT, LINE_ID, TARGET, null, DATE)).isEmpty();
    assertThat(service.forTransfer(STATEMENT, LINE_ID, TARGET, " ", DATE)).isEmpty();
    verifyNoInteractions(accountService, crossCurrencyFieldsService);
  }
}
