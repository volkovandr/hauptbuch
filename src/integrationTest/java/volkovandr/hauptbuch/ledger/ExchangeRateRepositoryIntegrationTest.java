package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.ExchangeRateRepository;

/**
 * Integration tier (CLAUDE.md §6): {@link ExchangeRateRepository#insertIfAbsent} — a plain insert
 * with an {@code on conflict do nothing} guard (§3.7), so it round-trips here rather than in the
 * SQL-logic tier ({@link ExchangeRateRepository#rateAsOf}'s carry-forward lookup owns that one).
 * V23 widens {@code source} to admit {@code 'import'} (plan e3).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ExchangeRateRepositoryIntegrationTest {

  private static final String CHF = "CHF";

  @Autowired ExchangeRateRepository exchangeRateRepository;

  @Test
  void insertIfAbsentInsertsAndTheRowReadsBack() {
    boolean inserted =
        exchangeRateRepository.insertIfAbsent(
            new ExchangeRate(
                null, CHF, LocalDate.of(2026, 5, 5), new BigDecimal("0.90000000"), "import"));

    assertThat(inserted).isTrue();
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2026, 5, 5)).orElseThrow())
        .isEqualByComparingTo("0.90");
  }

  @Test
  void insertIfAbsentNeverOverwritesAnExistingRowForThatDay() {
    exchangeRateRepository.insertIfAbsent(
        new ExchangeRate(
            null, CHF, LocalDate.of(2026, 6, 6), new BigDecimal("0.90000000"), "manual"));

    boolean insertedSecond =
        exchangeRateRepository.insertIfAbsent(
            new ExchangeRate(
                null, CHF, LocalDate.of(2026, 6, 6), new BigDecimal("0.50000000"), "import"));

    assertThat(insertedSecond).isFalse();
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2026, 6, 6)).orElseThrow())
        .isEqualByComparingTo("0.90");
  }
}
