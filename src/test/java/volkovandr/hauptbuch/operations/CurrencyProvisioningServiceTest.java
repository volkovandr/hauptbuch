package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.accounts.Account;
import volkovandr.hauptbuch.accounts.AccountService;
import volkovandr.hauptbuch.ledger.Currency;
import volkovandr.hauptbuch.ledger.CurrencyService;

/**
 * Unit tier (plan §1.5): the add-a-currency operation's orchestration with the DB mocked — the
 * currency insert is followed by exactly one system leaf, hung under the {@code Opening Balances}
 * parent with the parent's own type and the new currency (plan stage 6d). {@code FX gain/loss} is
 * no longer provisioned (auto-booking retired, data-model §6.3); category parents are likewise
 * never touched (leaves stay lazy, data-model §6.5).
 */
@ExtendWith(MockitoExtension.class)
class CurrencyProvisioningServiceTest {

  private static final String OPENING_BALANCES = "Opening Balances";
  private static final String NOK = "NOK";
  private static final String KRONE = "Norwegian Krone";
  private static final String KR = "kr";
  private static final long OPENING_PARENT_ID = 100L;

  @Mock private CurrencyService currencyService;
  @Mock private AccountService accountService;

  private CurrencyProvisioningService service;

  @BeforeEach
  void setUp() {
    service = new CurrencyProvisioningService(currencyService, accountService);
  }

  private static Account topLevel(long id, String name, String type) {
    return new Account(id, name, type, null, "EUR", null, null, null, null);
  }

  @Test
  void createsCurrencyThenOpeningBalancesLeafUnderItsParent() {
    Currency nok = new Currency(NOK, 2, KR, KRONE);
    when(currencyService.insert(NOK, 2, KR, KRONE)).thenReturn(nok);
    when(accountService.findTopLevelByName(OPENING_BALANCES))
        .thenReturn(Optional.of(topLevel(OPENING_PARENT_ID, OPENING_BALANCES, "equity")));

    Currency result = service.createCurrency(NOK, 2, KR, KRONE);

    assertThat(result).isEqualTo(nok);
    verify(accountService).insertLeaf("Opening Balances NOK", "equity", OPENING_PARENT_ID, NOK);
  }

  @Test
  void doesNotProvisionAnyLeafWhenCurrencyInsertRejects() {
    when(currencyService.insert(anyString(), anyInt(), any(), anyString()))
        .thenThrow(new IllegalArgumentException("Currency 'EUR' already exists"));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> service.createCurrency("EUR", 2, "€", "Euro"))
        .withMessageContaining("already exists");

    verify(accountService, never()).insertLeaf(any(), any(), any(), any());
  }

  @Test
  void failsWhenSystemParentIsMissing() {
    Currency nok = new Currency(NOK, 2, KR, KRONE);
    when(currencyService.insert(NOK, 2, KR, KRONE)).thenReturn(nok);
    when(accountService.findTopLevelByName(OPENING_BALANCES)).thenReturn(Optional.empty());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> service.createCurrency(NOK, 2, KR, KRONE))
        .withMessageContaining("Opening Balances");
  }
}
