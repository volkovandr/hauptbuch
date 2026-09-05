package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.repository.ExchangeRateRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ExchangeRateService#recordObservedRate} (import.md §6.3; plan
 * e3) with both dependencies mocked — the decision logic (which currency is base, whether the pair
 * states a rate at all) lives here in Java, not SQL, so it belongs in this tier. {@link
 * ExchangeRateRepository#insertIfAbsent}'s own round-trip lives in {@code
 * ExchangeRateRepositoryIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

  private static final LocalDate DATE = LocalDate.of(2026, 5, 5);

  @Mock ExchangeRateRepository exchangeRateRepository;
  @Mock SettingsService settingsService;

  private ExchangeRateService service() {
    return new ExchangeRateService(exchangeRateRepository, settingsService);
  }

  @Test
  void recordsTheImpliedRateWhenTheFirstCurrencyIsBase() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));

    service()
        .recordObservedRate(DATE, "EUR", new BigDecimal("100.00"), "CHF", new BigDecimal("150.00"));

    ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
    verify(exchangeRateRepository).insertIfAbsent(captor.capture());
    ExchangeRate rate = captor.getValue();
    assertThat(rate.currencyCode()).isEqualTo("CHF");
    assertThat(rate.date()).isEqualTo(DATE);
    assertThat(rate.rate())
        .isEqualByComparingTo(
            new BigDecimal("100.00").divide(new BigDecimal("150.00"), 8, RoundingMode.HALF_UP));
    assertThat(rate.source()).isEqualTo("import");
  }

  @Test
  void recordsTheImpliedRateWhenTheSecondCurrencyIsBase() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));

    service()
        .recordObservedRate(DATE, "CHF", new BigDecimal("150.00"), "EUR", new BigDecimal("100.00"));

    ArgumentCaptor<ExchangeRate> captor = ArgumentCaptor.forClass(ExchangeRate.class);
    verify(exchangeRateRepository).insertIfAbsent(captor.capture());
    ExchangeRate rate = captor.getValue();
    assertThat(rate.currencyCode()).isEqualTo("CHF");
    assertThat(rate.rate())
        .isEqualByComparingTo(
            new BigDecimal("100.00").divide(new BigDecimal("150.00"), 8, RoundingMode.HALF_UP));
  }

  @Test
  void doesNothingWhenNeitherCurrencyIsBase() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("USD"));

    service()
        .recordObservedRate(DATE, "EUR", new BigDecimal("100.00"), "CHF", new BigDecimal("150.00"));

    verify(exchangeRateRepository, never()).insertIfAbsent(any());
  }

  @Test
  void doesNothingWithoutBaseCurrencySetYet() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    service()
        .recordObservedRate(DATE, "EUR", new BigDecimal("100.00"), "CHF", new BigDecimal("150.00"));

    verify(exchangeRateRepository, never()).insertIfAbsent(any());
  }

  @Test
  void doesNothingWhenTheForeignAmountIsZero() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));

    service().recordObservedRate(DATE, "EUR", new BigDecimal("100.00"), "CHF", BigDecimal.ZERO);

    verify(exchangeRateRepository, never()).insertIfAbsent(any());
  }

  @Test
  void doesNothingWhenTheTwoCurrenciesAreTheSame() {
    // Refused before even consulting the base currency — there is nothing to convert regardless.
    service()
        .recordObservedRate(DATE, "EUR", new BigDecimal("100.00"), "EUR", new BigDecimal("100.00"));

    verifyNoInteractions(settingsService);
    verify(exchangeRateRepository, never()).insertIfAbsent(any());
  }
}
