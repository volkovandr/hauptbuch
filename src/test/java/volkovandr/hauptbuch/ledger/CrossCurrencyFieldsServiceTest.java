package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tier (plan §1.5): the entry dock's cross-currency amount-field layout (register §3.5/§3.8a,
 * plan stage 7d.1) — whether a category-currency override declares the transaction cross-currency,
 * whether a third base-amount field is needed, and the rate-based prefill for it.
 */
@ExtendWith(MockitoExtension.class)
class CrossCurrencyFieldsServiceTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String USD = "USD";
  private static final LocalDate DATE = LocalDate.of(2026, 2, 1);

  @Mock private SettingsService settingsService;
  @Mock private ExchangeRateService exchangeRateService;

  private CrossCurrencyFieldsService service;

  @BeforeEach
  void setUp() {
    service = new CrossCurrencyFieldsService(settingsService, exchangeRateService);
  }

  private static CrossCurrencyFieldsQuery query(
      String fundingCurrency,
      String categoryCurrency,
      String fundingAmount,
      String categoryAmount,
      String baseAmount) {
    return new CrossCurrencyFieldsQuery(
        fundingCurrency, categoryCurrency, DATE, fundingAmount, categoryAmount, baseAmount);
  }

  @Test
  void noOverrideStaysSingleCurrencyWithNoExtraFields() {
    CrossCurrencyFields fields = service.resolve(query(EUR, null, "20", null, null));

    assertThat(fields.crossCurrency()).isFalse();
    assertThat(fields.neitherIsBase()).isFalse();
    assertThat(fields.categoryCurrencyCode()).isEqualTo(EUR);
  }

  @Test
  void sameCurrencyAsFundingStaysSingleCurrencyEvenWhenExplicitlyResubmitted() {
    CrossCurrencyFields fields = service.resolve(query(EUR, EUR, "20", null, null));

    assertThat(fields.crossCurrency()).isFalse();
  }

  @Test
  void overridingToAnotherCurrencyDeclaresCrossCurrencyWithNoBaseFieldWhenFundingIsBase() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    CrossCurrencyFields fields = service.resolve(query(EUR, CHF, "9,10", "10", null));

    assertThat(fields.crossCurrency()).isTrue();
    assertThat(fields.neitherIsBase()).isFalse();
    assertThat(fields.categoryCurrencyCode()).isEqualTo(CHF);
    assertThat(fields.categoryAmountText()).isEqualTo("10");
    assertThat(fields.baseAmountText()).isNull();
  }

  @Test
  void overridingToAnotherCurrencyDeclaresCrossCurrencyWithNoBaseFieldWhenCategoryIsBase() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    CrossCurrencyFields fields = service.resolve(query(CHF, EUR, "10", "9,10", null));

    assertThat(fields.crossCurrency()).isTrue();
    assertThat(fields.neitherIsBase()).isFalse();
  }

  @Test
  void neitherLegBaseNeedsTheThirdField() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    CrossCurrencyFields fields = service.resolve(query(CHF, USD, "9", "10", null));

    assertThat(fields.crossCurrency()).isTrue();
    assertThat(fields.neitherIsBase()).isTrue();
  }

  @Test
  void neitherLegBasePrefillsTheBaseAmountFromTheRateFeed() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(exchangeRateService.rateAsOf(CHF, DATE)).thenReturn(Optional.of(new BigDecimal("0.95")));

    CrossCurrencyFields fields = service.resolve(query(CHF, USD, "10", "10,80", null));

    assertThat(fields.baseAmountText()).isEqualTo("9,50");
  }

  @Test
  void prefillStripsAnExplicitSignOverrideBeforeMultiplying() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(exchangeRateService.rateAsOf(CHF, DATE)).thenReturn(Optional.of(new BigDecimal("0.95")));

    CrossCurrencyFields fields = service.resolve(query(CHF, USD, "+10", "10,80", null));

    assertThat(fields.baseAmountText()).isEqualTo("9,50");
  }

  @Test
  void prefillIsBlankWithoutStoredRate() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));
    when(exchangeRateService.rateAsOf(CHF, DATE)).thenReturn(Optional.empty());

    CrossCurrencyFields fields = service.resolve(query(CHF, USD, "10", "10,80", null));

    assertThat(fields.baseAmountText()).isNull();
  }

  @Test
  void priorBaseAmountIsRedisplayedRatherThanRePrefilled() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of(EUR));

    CrossCurrencyFields fields = service.resolve(query(CHF, USD, "10", "10,80", "8,50"));

    assertThat(fields.baseAmountText()).isEqualTo("8,50");
  }

  @Test
  void withoutBaseCurrencySetNeitherIsBaseStaysFalse() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());

    CrossCurrencyFields fields = service.resolve(query(CHF, USD, "10", "10,80", null));

    assertThat(fields.neitherIsBase()).isFalse();
  }
}
