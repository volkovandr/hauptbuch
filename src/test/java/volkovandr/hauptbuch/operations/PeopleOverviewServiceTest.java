package volkovandr.hauptbuch.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.debts.CurrencyBalance;
import volkovandr.hauptbuch.debts.PersonBalanceSummary;
import volkovandr.hauptbuch.debts.PersonService;
import volkovandr.hauptbuch.ledger.ExchangeRateService;
import volkovandr.hauptbuch.ledger.SettingsService;
import volkovandr.hauptbuch.operations.PeopleOverview.PersonRow;

/**
 * Unit tier (CLAUDE.md §6): the People-balances assembler's own logic — the base-currency valuation
 * (native × rate@today, base at 1), the direction wording, and when the base gloss is suppressed —
 * with {@code debts} and {@code ledger} mocked. The per-currency figures come straight from {@code
 * debts}; only the base total is computed here.
 */
class PeopleOverviewServiceTest {

  private final PersonService personService = mock();
  private final SettingsService settingsService = mock();
  private final ExchangeRateService exchangeRateService = mock();
  private final PeopleOverviewService service =
      new PeopleOverviewService(personService, settingsService, exchangeRateService);

  private void baseIsEur() {
    when(settingsService.baseCurrency()).thenReturn(Optional.of("EUR"));
  }

  @Test
  void valuesMixedCurrencyPositionInBaseAtTodaysRate() {
    baseIsEur();
    // You owe €10, Max owes you 5 CHF; CHF@today = 0,90 base → total = −10 + 4,50 = −5,50.
    when(exchangeRateService.rateAsOf(eq("CHF"), any()))
        .thenReturn(Optional.of(new BigDecimal("0.90")));
    when(personService.balanceSummaries())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(
                    1L,
                    "Max",
                    List.of(
                        new CurrencyBalance("EUR", new BigDecimal("-10.00")),
                        new CurrencyBalance("CHF", new BigDecimal("5.00"))),
                    List.of(100L, 101L))));

    PersonRow max = service.overview().people().get(0);

    assertThat(max.lines()).hasSize(2);
    assertThat(max.lines().get(0).amount()).isEqualTo("-10,00");
    assertThat(max.lines().get(0).negative()).isTrue();
    assertThat(max.lines().get(0).direction()).isEqualTo("You owe 10,00");
    assertThat(max.lines().get(1).amount()).isEqualTo("5,00 CHF");
    assertThat(max.lines().get(1).negative()).isFalse();
    assertThat(max.lines().get(1).direction()).isEqualTo("Max owes you 5,00 CHF");
    assertThat(max.baseTotalShown()).isTrue();
    assertThat(max.baseTotal()).isEqualTo("-5,50");
    assertThat(max.baseTotalNegative()).isTrue();
    assertThat(max.accountIds()).containsExactly(100L, 101L);
  }

  @Test
  void showsBaseGlossForSingleBaseCurrencyPosition() {
    baseIsEur();
    when(personService.balanceSummaries())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(
                    2L,
                    "Ben",
                    List.of(new CurrencyBalance("EUR", new BigDecimal("25.00"))),
                    List.of(200L))));

    PersonRow ben = service.overview().people().get(0);

    assertThat(ben.baseTotalShown()).isTrue();
    assertThat(ben.baseTotal()).isEqualTo("25,00");
    assertThat(ben.baseTotalNegative()).isFalse();
  }

  @Test
  void settledPersonIsSettledWithNoBaseGloss() {
    baseIsEur();
    when(personService.balanceSummaries()).thenReturn(List.of(summarySettled()));

    PersonRow anna = service.overview().people().get(0);

    assertThat(anna.lines()).isEmpty();
    assertThat(anna.baseTotalShown()).isFalse();
  }

  @Test
  void suppressesBaseGlossWhenCurrencyHasNoRate() {
    baseIsEur();
    // No stub for GBP → rateAsOf returns Optional.empty(): the total cannot be completed, so the
    // gloss is suppressed rather than shown partial.
    when(personService.balanceSummaries())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(
                    3L,
                    "Cara",
                    List.of(new CurrencyBalance("GBP", new BigDecimal("5.00"))),
                    List.of(300L))));

    PersonRow cara = service.overview().people().get(0);

    assertThat(cara.baseTotalShown()).isFalse();
    assertThat(cara.lines().get(0).amount()).contains("5,00");
  }

  @Test
  void suppressesBaseGlossWhenNoBaseCurrencySet() {
    when(settingsService.baseCurrency()).thenReturn(Optional.empty());
    when(personService.balanceSummaries())
        .thenReturn(
            List.of(
                new PersonBalanceSummary(
                    4L,
                    "Dee",
                    List.of(new CurrencyBalance("EUR", new BigDecimal("5.00"))),
                    List.of(400L))));

    PersonRow dee = service.overview().people().get(0);

    assertThat(dee.baseTotalShown()).isFalse();
  }

  private static PersonBalanceSummary summarySettled() {
    return new PersonBalanceSummary(9L, "Anna", List.of(), List.of(900L));
  }
}
