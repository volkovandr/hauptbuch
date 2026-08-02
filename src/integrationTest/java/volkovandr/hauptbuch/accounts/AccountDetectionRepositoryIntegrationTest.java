package volkovandr.hauptbuch.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.accounts.repository.AccountRepository;

/**
 * Integration tier (§1.5): the stage-9e paying-account detection round-trips (data-model §13.4) —
 * the card-last-4 and cash-account config write, read, and the lookups the analyse worker uses.
 * Flyway applies V12; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountDetectionRepositoryIntegrationTest {

  @Autowired AccountRepository accountRepository;

  private long asset(String name) {
    return accountRepository.insert(
        new Account(null, name, "asset", null, "EUR", null, null, null, null, false, false));
  }

  @Test
  void updateAndFindDetectionRoundTrip() {
    long id = asset("EC Card");

    accountRepository.updateDetection(id, "1234", false);

    AccountDetection detection = accountRepository.findDetection(id).orElseThrow();
    assertThat(detection.cardLast4()).isEqualTo("1234");
    assertThat(detection.cashAccount()).isFalse();
  }

  @Test
  void findByCardLast4MatchesTheConfiguredAccount() {
    long id = asset("Visa");
    accountRepository.updateDetection(id, "9876", false);

    assertThat(accountRepository.findByCardLast4("9876").orElseThrow().accountId()).isEqualTo(id);
    assertThat(accountRepository.findByCardLast4("0000")).isEmpty();
  }

  @Test
  void findCashAccountMatchesTheMarkedAccount() {
    long id = asset("Wallet");
    accountRepository.updateDetection(id, null, true);

    assertThat(accountRepository.findCashAccount().orElseThrow().accountId()).isEqualTo(id);
  }

  @Test
  void defaultsAreNoCardAndNotCash() {
    long id = asset("Plain");

    AccountDetection detection = accountRepository.findDetection(id).orElseThrow();
    assertThat(detection.cardLast4()).isNull();
    assertThat(detection.cashAccount()).isFalse();
  }
}
