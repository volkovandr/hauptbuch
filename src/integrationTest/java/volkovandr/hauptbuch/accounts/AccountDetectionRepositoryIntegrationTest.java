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
 * Integration tier (§1.5): the paying-account detection config round-trips (data-model §13.4) — the
 * label list and cash marker the account-edit screen writes, read back as the record. The candidate
 * lookup the analyse worker uses is ordering logic and lives in the SQL-logic tier instead. Flyway
 * applies V16; each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class AccountDetectionRepositoryIntegrationTest {

  @Autowired AccountRepository accountRepository;

  private long asset(String name) {
    return accountRepository.insert(
        new Account(null, name, "asset", null, "EUR", null, null, null, null, false, false, false));
  }

  @Test
  void updateAndFindDetectionRoundTrip() {
    long id = asset("EC Card");

    accountRepository.updateDetection(id, "card, 1234", false);

    AccountDetection detection = accountRepository.findDetection(id).orElseThrow();
    assertThat(detection.detectionLabels()).isEqualTo("card, 1234");
    assertThat(detection.cashAccount()).isFalse();
  }

  @Test
  void updateDetectionClearsTheLabels() {
    long id = asset("Visa");
    accountRepository.updateDetection(id, "9876", false);

    accountRepository.updateDetection(id, null, true);

    AccountDetection detection = accountRepository.findDetection(id).orElseThrow();
    assertThat(detection.detectionLabels()).isNull();
    assertThat(detection.cashAccount()).isTrue();
  }

  @Test
  void detectionCandidateCarriesTheColumnsTheDetectorReads() {
    long id = asset("Girocard");
    accountRepository.updateDetection(id, "card, 1234", false);

    AccountDetectionCandidate candidate =
        accountRepository.findDetectionCandidates("EUR").stream()
            .filter(c -> c.accountId() == id)
            .findFirst()
            .orElseThrow();

    assertThat(candidate.detectionLabels()).isEqualTo("card, 1234");
    assertThat(candidate.currencyCode()).isEqualTo("EUR");
    assertThat(candidate.cashAccount()).isFalse();
  }

  @Test
  void defaultsAreNoLabelsAndNotCash() {
    long id = asset("Plain");

    AccountDetection detection = accountRepository.findDetection(id).orElseThrow();
    assertThat(detection.detectionLabels()).isNull();
    assertThat(detection.cashAccount()).isFalse();
  }
}
