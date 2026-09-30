package volkovandr.hauptbuch.recurring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.SplitForm;

/**
 * Unit tier (CLAUDE.md §6): which rate a template's typed cross-currency totals state, and that a
 * blank (proposed) or single-currency entry states none.
 */
@ExtendWith(MockitoExtension.class)
class RecurringEnteredRateTest {

  private static final LocalDate START = LocalDate.of(2026, 9, 30);
  private static final long ACCOUNT_ID = 7L;
  private static final String EUR = "EUR";
  private static final String USD = "USD";
  private static final String CHF = "CHF";

  @Mock private AccountService accountService;
  @Mock private SettingsService settingsService;
  @Mock private ExchangeRateService exchangeRateService;

  private RecurringEnteredRate enteredRate;

  @BeforeEach
  void setUp() {
    enteredRate = new RecurringEnteredRate(accountService, settingsService, exchangeRateService);
  }

  private void accountIn(String currency) {
    when(accountService.findById(ACCOUNT_ID))
        .thenReturn(
            Optional.of(
                new Account(
                    ACCOUNT_ID,
                    "BankAaa",
                    "asset",
                    null,
                    currency,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    true)));
  }

  private static SplitForm split(String total, String fundingTotal, String baseTotal) {
    return new SplitForm(
        null,
        START,
        ACCOUNT_ID,
        "",
        "",
        "",
        "",
        "",
        total,
        null,
        fundingTotal,
        baseTotal,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false);
  }

  @Test
  void fundingInBaseStatesTheSpendingCurrencyRate() {
    accountIn(EUR);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    enteredRate.record(START, split("10,00", "9,00", ""), USD);

    verify(exchangeRateService)
        .recordEnteredRate(START, USD, new BigDecimal("10.00"), new BigDecimal("9.00"));
  }

  @Test
  void spendingInBaseStatesTheFundingCurrencyRate() {
    accountIn(USD);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    enteredRate.record(START, split("9,00", "10,00", ""), EUR);

    verify(exchangeRateService)
        .recordEnteredRate(START, USD, new BigDecimal("10.00"), new BigDecimal("9.00"));
  }

  @Test
  void neitherInBaseStatesBothRatesAgainstTheBaseTotal() {
    accountIn(CHF);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    enteredRate.record(START, split("10,00", "8,00", "9,00"), USD);

    verify(exchangeRateService)
        .recordEnteredRate(START, USD, new BigDecimal("10.00"), new BigDecimal("9.00"));
    verify(exchangeRateService)
        .recordEnteredRate(START, CHF, new BigDecimal("8.00"), new BigDecimal("9.00"));
  }

  @Test
  void blankFundingTotalIsProposalAndStatesNothing() {
    accountIn(EUR);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    enteredRate.record(START, split("10,00", "", ""), USD);

    verify(exchangeRateService, never()).recordEnteredRate(any(), any(), any(), any());
  }

  @Test
  void singleCurrencyEntryStatesNothing() {
    accountIn(EUR);
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    enteredRate.record(START, split("10,00", "", ""), EUR);

    verify(exchangeRateService, never()).recordEnteredRate(any(), any(), any(), any());
  }
}
