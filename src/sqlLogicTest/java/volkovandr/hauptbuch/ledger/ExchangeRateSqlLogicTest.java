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
 * SQL-logic tier (plan §1.5): the carry-forward lookup {@link ExchangeRateRepository#rateAsOf} —
 * "the most recent stored rate on or before D". The rate table is sparse (monthly ECB + occasional
 * manual rows); gaps are filled by an {@code order by date desc limit 1} carry-forward, which is
 * derived-value logic that lives in the SQL rather than a row-mapping round-trip (so it sits here,
 * not in the integration tier). This lookup is the FX foundation — it proposes rates on entry and
 * revalues held balances.
 *
 * <p>This suite boots a Spring context (via {@link TestcontainersConfiguration}) so the query under
 * test is the real repository SQL; {@code @Transactional} rolls each test back on the reused
 * container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ExchangeRateSqlLogicTest {

  private static final String CHF = "CHF";

  @Autowired ExchangeRateRepository exchangeRateRepository;

  @Test
  void rateAsOfCarriesTheMostRecentPriorRateForwardAcrossGaps() {
    exchangeRateRepository.insert(
        new ExchangeRate(
            null, CHF, LocalDate.of(2026, 1, 1), new BigDecimal("0.90000000"), "manual"));
    exchangeRateRepository.insert(
        new ExchangeRate(
            null, CHF, LocalDate.of(2026, 3, 1), new BigDecimal("0.95000000"), "manual"));

    // On a gap date (mid-Feb, between the two stored rows), the Jan rate carries forward.
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2026, 2, 15)).orElseThrow())
        .isEqualByComparingTo("0.90");
    // On the exact date of a stored row, that row wins (on-or-before is inclusive).
    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2026, 3, 1)).orElseThrow())
        .isEqualByComparingTo("0.95");
  }

  @Test
  void rateAsOfIsEmptyBeforeTheFirstStoredRate() {
    exchangeRateRepository.insert(
        new ExchangeRate(
            null, CHF, LocalDate.of(2026, 1, 1), new BigDecimal("0.90000000"), "manual"));

    assertThat(exchangeRateRepository.rateAsOf(CHF, LocalDate.of(2025, 12, 31))).isEmpty();
  }
}
