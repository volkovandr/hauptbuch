package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Unit tier (plan §1.5): the paying-account detection signal parsing (data-model §13.4, stage 9e) —
 * cash/Bar → the marked cash account, a card slip's last-4 → the matching account, blank/short →
 * empty. The repository lookups are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayingAccountDetectorTest {

  @Mock private AccountRepository accountRepository;

  private static Account account(long id) {
    return new Account(id, "n", "asset", null, "EUR", null, null, null, null, false, false);
  }

  private PayingAccountDetector detector() {
    return new PayingAccountDetector(accountRepository);
  }

  @Test
  void cashSignalResolvesToTheMarkedCashAccount() {
    when(accountRepository.findCashAccount()).thenReturn(Optional.of(account(1L)));

    assertThat(detector().detect("Bar").orElseThrow().accountId()).isEqualTo(1L);
    assertThat(detector().detect("cash").orElseThrow().accountId()).isEqualTo(1L);
  }

  @Test
  void cardSlipResolvesByLastFourDigits() {
    when(accountRepository.findByCardLast4("1234")).thenReturn(Optional.of(account(2L)));

    assertThat(detector().detect("card XXXX1234").orElseThrow().accountId()).isEqualTo(2L);
  }

  @Test
  void blankOrTooShortResolvesEmpty() {
    assertThat(detector().detect(null)).isEmpty();
    assertThat(detector().detect("  ")).isEmpty();
    assertThat(detector().detect("12")).isEmpty();
  }
}
