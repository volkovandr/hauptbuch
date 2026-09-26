package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.RunningColumn.BalanceRun;
import volkovandr.hauptbuch.analytics.RunningColumn.Entry;
import volkovandr.hauptbuch.analytics.RunningColumn.OpeningPart;
import volkovandr.hauptbuch.analytics.repository.PostingValue;

/**
 * Unit tier (CLAUDE.md §6): {@link RunningColumn} — a drill-down list's running column
 * (reporting.md §12), row by row. That the last row lands on its figure for real data is {@code
 * ReportDrillDownSqlLogicTest}'s job; this pins the rows in between.
 */
class RunningColumnTest {

  private static final Measure BASE_NET = Measure.turnover(PresentationCurrency.BASE, Leg.NET);
  private static final Measure NATIVE_NET = Measure.turnover(PresentationCurrency.ACCOUNT, Leg.NET);
  private static final Measure BASE_CLOSING = Measure.closingBalance(PresentationCurrency.BASE);
  private static final Measure NATIVE_CLOSING =
      Measure.closingBalance(PresentationCurrency.ACCOUNT);
  private static final Map<String, BigDecimal> CHF_AT_095 = Map.of("CHF", new BigDecimal("0.95"));

  private static Entry entry(
      long transactionId, String currency, String amount, String base, boolean flip, int addend) {
    PostingValue posting =
        new PostingValue(
            transactionId * 10,
            transactionId,
            LocalDate.of(2026, 1, 5),
            currency,
            new BigDecimal(amount),
            base == null ? null : new BigDecimal(base));
    return new Entry(posting, flip, addend);
  }

  private static Cell eur(String amount) {
    return new Cell.Value(new BigDecimal(amount), "EUR");
  }

  @Test
  void accumulatesBaseValuesFlippingEachPostingAsItsCellIs() {
    List<Cell> running =
        RunningColumn.of(
            BASE_NET,
            "EUR",
            List.of(
                entry(1, "EUR", "-1000.00", "-1000.00", true, 0),
                entry(2, "CHF", "10.00", "9.00", false, 1),
                entry(3, "EUR", "-20.00", "-20.00", false, 1)));

    assertThat(running).containsExactly(eur("1000.00"), eur("1009.00"), eur("989.00"));
  }

  @Test
  void turnsBaseDashFromTheFirstPostingWithNoRateOnward() {
    List<Cell> running =
        RunningColumn.of(
            BASE_NET,
            "EUR",
            List.of(
                entry(1, "EUR", "5.00", "5.00", false, 0),
                entry(2, "USD", "4.00", null, false, 0),
                entry(3, "EUR", "1.00", "1.00", false, 0)));

    Cell missing = new Cell.Illegal(Cell.Reason.MISSING_RATE);
    assertThat(running.get(0)).isInstanceOf(Cell.Value.class);
    assertThat(running.subList(1, 3)).containsExactly(missing, missing);
  }

  @Test
  void accumulatesNativeAmountsUntilSecondCurrencyJoins() {
    List<Cell> running =
        RunningColumn.of(
            NATIVE_NET,
            "EUR",
            List.of(
                entry(1, "CHF", "10.00", "9.00", false, 0),
                entry(2, "CHF", "2.50", "2.25", false, 0),
                entry(3, "EUR", "1.00", "1.00", false, 0)));

    assertThat(running.get(1)).isEqualTo(new Cell.Value(new BigDecimal("12.50"), "CHF"));
    assertThat(running.get(2)).isEqualTo(new Cell.Illegal(Cell.Reason.MULTI_CURRENCY));
  }

  @Test
  void countsEveryPosting() {
    List<Cell> running =
        RunningColumn.of(
            Measure.countPostings(),
            "EUR",
            List.of(
                entry(1, "EUR", "20.00", "20.00", false, 0),
                entry(1, "EUR", "10.00", "10.00", false, 0)));

    assertThat(running).containsExactly(new Cell.Count(1), new Cell.Count(2));
  }

  @Test
  void countsTransactionsOncePerCellAndCurrencyTouched() {
    List<Cell> running =
        RunningColumn.of(
            Measure.countTransactions(),
            "EUR",
            List.of(
                entry(1, "EUR", "20.00", "20.00", false, 0),
                entry(1, "EUR", "10.00", "10.00", false, 0), // same cell, same currency
                entry(1, "CHF", "5.00", "4.50", false, 0), // same cell, another currency
                entry(1, "EUR", "7.00", "7.00", false, 1))); // another cell of a total

    assertThat(running)
        .containsExactly(
            new Cell.Count(1), new Cell.Count(1), new Cell.Count(2), new Cell.Count(3));
  }

  @Test
  void refusesClosingBalance() {
    assertThatThrownBy(
            () ->
                RunningColumn.of(
                    Measure.closingBalance(PresentationCurrency.BASE), "EUR", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── closing balance (§12: an opening line, then the period, all at the period-end rate) ──

  private static OpeningPart opening(String currency, String amount, boolean periodPostings) {
    return new OpeningPart(currency, new BigDecimal(amount), periodPostings, false);
  }

  @Test
  void valuesTheOpeningLineAndEveryRowAtThePeriodEndRate() {
    BalanceRun run =
        RunningColumn.closingBalance(
            BASE_CLOSING,
            "EUR",
            List.of(opening("EUR", "50.00", false), opening("CHF", "90.00", true)),
            List.of(entry(1, "CHF", "-30.00", "-27.00", false, 0)),
            CHF_AT_095);

    assertThat(run.opening()).isEqualTo(eur("135.5000"));
    assertThat(run.running()).containsExactly(eur("107.0000"));
  }

  @Test
  void flipsTheOpeningLineAndEachRowAsTheirCellIs() {
    BalanceRun run =
        RunningColumn.closingBalance(
            BASE_CLOSING,
            "EUR",
            List.of(new OpeningPart("EUR", new BigDecimal("-40.00"), true, true)),
            List.of(entry(1, "EUR", "40.00", "40.00", true, 0)),
            Map.of());

    assertThat(run.opening()).isEqualTo(eur("40.00"));
    assertThat(run.running()).containsExactly(eur("0.00"));
  }

  @Test
  void opensOnDashWhenBalanceBeforeThePeriodHasNoRate() {
    BalanceRun run =
        RunningColumn.closingBalance(
            BASE_CLOSING,
            "EUR",
            List.of(opening("USD", "10.00", false), opening("EUR", "5.00", true)),
            List.of(entry(1, "EUR", "-1.00", "-1.00", false, 0)),
            Map.of());

    Cell dash = new Cell.Illegal(Cell.Reason.MISSING_RATE);
    assertThat(run.opening()).isEqualTo(dash);
    assertThat(run.running()).containsExactly(dash);
  }

  @Test
  void turnsBaseDashAtTheFirstRowInCurrencyWithNoRate() {
    BalanceRun run =
        RunningColumn.closingBalance(
            BASE_CLOSING,
            "EUR",
            List.of(opening("EUR", "5.00", true), opening("USD", "0.00", true)),
            List.of(
                entry(1, "EUR", "-1.00", "-1.00", false, 0),
                entry(2, "USD", "10.00", null, false, 0)),
            Map.of());

    assertThat(run.opening()).isEqualTo(eur("5.00"));
    assertThat(run.running())
        .containsExactly(eur("4.00"), new Cell.Illegal(Cell.Reason.MISSING_RATE));
  }

  @Test
  void nativeOpensInItsOneCurrencyAndTurnsDashAtTheSecond() {
    BalanceRun run =
        RunningColumn.closingBalance(
            NATIVE_CLOSING,
            "EUR",
            List.of(opening("CHF", "90.00", true), opening("EUR", "0.00", true)),
            List.of(
                entry(1, "CHF", "-30.00", "-27.00", false, 0),
                entry(2, "EUR", "5.00", "5.00", false, 0)),
            CHF_AT_095);

    assertThat(run.opening()).isEqualTo(new Cell.Value(new BigDecimal("90.00"), "CHF"));
    assertThat(run.running())
        .containsExactly(
            new Cell.Value(new BigDecimal("60.00"), "CHF"),
            new Cell.Illegal(Cell.Reason.MULTI_CURRENCY));
  }

  @Test
  void nativeOpensOnDashWhenBalancesBeforeThePeriodSpanTwoCurrencies() {
    BalanceRun run =
        RunningColumn.closingBalance(
            NATIVE_CLOSING,
            "EUR",
            List.of(opening("CHF", "90.00", false), opening("EUR", "10.00", true)),
            List.of(),
            CHF_AT_095);

    assertThat(run.opening()).isEqualTo(new Cell.Illegal(Cell.Reason.MULTI_CURRENCY));
    assertThat(run.running()).isEmpty();
  }
}
