package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.ledger.repository.PinnedBalance;
import volkovandr.hauptbuch.ledger.repository.PinnedBalanceRepository;

/**
 * Unit tier (CLAUDE.md §6): the Balances-panel assembler's own logic — the base-currency bracket
 * and total valuation (native × rate@today, base at 1), and when the panel and its Total row are
 * suppressed — with the repository, settings and rates mocked. The SQL that produces the pinned
 * balances and their order is {@link volkovandr.hauptbuch.ledger.PinnedBalanceSqlLogicTest}'s job.
 */
class BalancesPanelServiceTest {

  private final PinnedBalanceRepository pinnedBalanceRepository = mock();
  private final SettingsService settingsService = mock();
  private final ExchangeRateService exchangeRateService = mock();
  private final BalancesPanelService service =
      new BalancesPanelService(pinnedBalanceRepository, settingsService, exchangeRateService);

  private void baseIsEur() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));
  }

  private static PinnedBalance pinned(long id, String name, String currency, String balance) {
    return new PinnedBalance(id, name, currency, 210, new BigDecimal(balance));
  }

  @Test
  void rendersNothingWhenNoBaseCurrencyIsSet() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    assertThat(service.current()).isEmpty();
  }

  @Test
  void rendersNothingWhenNothingIsPinned() {
    baseIsEur();
    when(pinnedBalanceRepository.findPinnedBalances()).thenReturn(List.of());

    assertThat(service.current()).isEmpty();
  }

  @Test
  void baseCurrencyRowIsBareWithNoBracketAndSinglePinnedAccountHasNoTotal() {
    baseIsEur();
    when(pinnedBalanceRepository.findPinnedBalances())
        .thenReturn(List.of(pinned(1L, "Giro", "EUR", "1234.56")));

    BalancesPanel panel = service.current().orElseThrow();

    assertThat(panel.rows())
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.amount()).isEqualTo("1.234,56");
              assertThat(row.negative()).isFalse();
            });
    assertThat(panel.totalShown()).isFalse();
  }

  @Test
  void nonBaseRowCarriesSymbolAndBracketedBaseEquivalentAndTotalSums() {
    baseIsEur();
    when(exchangeRateService.rateAsOf(eq("CHF"), any()))
        .thenReturn(Optional.of(new BigDecimal("0.92")));
    when(pinnedBalanceRepository.findPinnedBalances())
        .thenReturn(
            List.of(
                pinned(1L, "Giro", "EUR", "1000.00"), pinned(2L, "Sparkonto", "CHF", "10000.00")));

    BalancesPanel panel = service.current().orElseThrow();

    assertThat(panel.rows().get(0).amount()).isEqualTo("1.000,00");
    assertThat(panel.rows().get(1).amount()).isEqualTo("10.000,00 CHF (9.200,00)");
    assertThat(panel.totalShown()).isTrue();
    // 1.000,00 + (10.000,00 × 0,92) = 10.200,00
    assertThat(panel.total()).isEqualTo("10.200,00");
    assertThat(panel.baseCurrencyCode()).isEqualTo("EUR");
  }

  @Test
  void missingRateShowsDashBracketAndSuppressesTheTotalEntirely() {
    baseIsEur();
    // No stub for GBP → rateAsOf empty.
    when(pinnedBalanceRepository.findPinnedBalances())
        .thenReturn(
            List.of(pinned(1L, "Giro", "EUR", "1000.00"), pinned(2L, "London", "GBP", "500.00")));

    BalancesPanel panel = service.current().orElseThrow();

    assertThat(panel.rows().get(1).amount()).startsWith("500,00 ").endsWith(" (—)");
    assertThat(panel.totalShown()).isFalse();
    assertThat(panel.total()).isEmpty();
  }

  @Test
  void negativeBalanceIsFlaggedAndRowsFollowTheRepositoryOrder() {
    baseIsEur();
    when(pinnedBalanceRepository.findPinnedBalances())
        .thenReturn(
            List.of(
                pinned(1L, "Anna Loan", "EUR", "-40.00"),
                pinned(2L, "Giro", "EUR", "12.00"),
                pinned(3L, "Zins", "EUR", "3.00")));

    BalancesPanel panel = service.current().orElseThrow();

    assertThat(panel.rows())
        .extracting(BalancesPanel.Row::name)
        .containsExactly("Anna Loan", "Giro", "Zins");
    assertThat(panel.rows().get(0).negative()).isTrue();
    assertThat(panel.totalShown()).isTrue();
    assertThat(panel.total()).isEqualTo("-25,00");
  }
}
