package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.analytics.repository.NodeKey;

/**
 * SQL-logic tier (CLAUDE.md §6): the closing-balance drill-down (reporting.md §12) — a list opens
 * on what the figure's accounts held before its period, lists the period's postings, and closes on
 * the figure, everything valued at the period-end rate as the figure is.
 */
class ClosingBalanceDrillDownSqlLogicTest extends DrillDownSqlLogicTestSupport {

  @Test
  void closingBalanceCellsOpenOnTheBalanceBeforeTheirPeriod() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long card = insertAccount("Card", "liability", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long salary = insertAccount("Salary", "income", EUR, null);
    move(salary, cash, LocalDate.of(2025, 12, 20), "1000.00"); // before the range
    spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    spend(card, food, LocalDate.of(2026, 1, 10), "40.00");
    long settle = insertTransaction(LocalDate.of(2026, 2, 3));
    final long settleCash = insertPosting(settle, cash, "-40.00");
    insertPosting(settle, card, "40.00");
    long lunch = insertTransaction(LocalDate.of(2026, 2, 9));
    final long lunchCash = insertPosting(lunch, cash, "-12.50");
    insertPosting(lunch, food, "12.50");
    ReportSpec spec =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            List.of(BASE_CLOSING),
            Scope.ofTypes("asset", "liability"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // Cash and Card in both months, then the two column totals; a row total adds balances across
    // time and has no posting set.
    assertThat(drilled).isEqualTo(4 + 2);
    ReportGrid grid = engine.render(spec, TODAY, AUTO);
    DrillDown cashFebruary =
        drillDown.drill(
            spec, AUTO, CellAddress.forBody(spec, grid, rowIndex(grid, "Cash"), 1), TODAY);
    assertThat(cashFebruary.opening().date()).isEqualTo(LocalDate.of(2026, 2, 1));
    assertSameFigure(
        cashFebruary.opening().running(), new Cell.Value(new BigDecimal("980.00"), EUR), null);
    assertThat(cashFebruary.rows())
        .extracting(row -> row.posting().postingId())
        .containsExactly(settleCash, lunchCash);
  }

  @Test
  void crossCurrencyClosingBalanceListsAreValuedAtThePeriodEndRate() {
    long equity = insertAccount("Opening", "equity", EUR, null);
    long cash = insertAccount("Cash", "asset", EUR, null);
    final long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    long food = insertAccount("Food", "expense", EUR, null);
    final long foodChf = insertAccount("Food-CHF", "expense", CHF, food);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    insertRate(CHF, LocalDate.of(2026, 2, 15), "0.95");
    move(equity, cash, LocalDate.of(2025, 12, 1), "50.00");
    long opening = insertTransaction(LocalDate.of(2025, 12, 10));
    insertPosting(opening, equity, "-90.00");
    insertPosting(opening, cashChf, "100.00", "90.00");
    spend(cashChf, foodChf, LocalDate.of(2026, 1, 10), "10.00");
    long frozen = insertTransaction(LocalDate.of(2026, 2, 20));
    insertPosting(frozen, cashChf, "-30.00", "-27.00"); // frozen, yet a balance is marked to market
    insertPosting(frozen, foodChf, "30.00", "27.00");
    ReportSpec spec =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            List.of(BASE_CLOSING, NATIVE_CLOSING),
            Scope.ofTypes("asset"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // Cash and Cash-CHF in both months, each in base and native; the base column totals, and the
    // native ones, which are — for adding two currencies yet still list their postings.
    assertThat(drilled).isEqualTo(8 + 4);
    ReportGrid grid = engine.render(spec, TODAY, AUTO);
    DrillDown chfFebruary =
        drillDown.drill(
            spec, AUTO, CellAddress.forBody(spec, grid, rowIndex(grid, "Cash-CHF"), 2), TODAY);
    // 90 CHF at the end of January, and 60 at the end of February, both at February's 0.95.
    assertSameFigure(
        chfFebruary.opening().running(), new Cell.Value(new BigDecimal("85.50"), EUR), null);
    assertSameFigure(chfFebruary.closing(), new Cell.Value(new BigDecimal("57.00"), EUR), null);
  }

  @Test
  void closingBalanceWithoutDateAxisOpensAtTheRangeStart() {
    long equity = insertAccount("Opening", "equity", EUR, null);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    final long cashUsd = insertAccount("Cash-USD", "asset", USD, null); // USD has no rate at all
    final long food = insertAccount("Food", "expense", EUR, null);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    move(equity, cash, LocalDate.of(2025, 12, 1), "50.00");
    move(equity, cashChf, LocalDate.of(2025, 12, 1), "20.00");
    final long lunch = spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    move(equity, cashUsd, LocalDate.of(2026, 2, 5), "10.00");
    ReportSpec spec =
        spec(List.of(Dimension.ACCOUNT), List.of(), List.of(BASE_CLOSING), Scope.ofTypes("asset"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // Cash, Cash-CHF and Cash-USD — whose — for a missing rate still lists its posting — their
    // three row totals, the column total and the grand total.
    assertThat(drilled).isEqualTo(3 + 3 + 1 + 1);
    ReportGrid grid = engine.render(spec, TODAY, AUTO);
    DrillDown cashRange =
        drillDown.drill(
            spec, AUTO, CellAddress.forBody(spec, grid, rowIndex(grid, "Cash"), 0), TODAY);
    assertThat(cashRange.opening().date()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(cashRange.rows())
        .extracting(row -> row.posting().postingId())
        .doesNotContain(lunch) // the food leg is not Cash's
        .hasSize(1);
  }

  @Test
  void closingBalanceDateRowsWithOneMonthExpandedIntoDaysClose() {
    long equity = insertAccount("Opening", "equity", EUR, null);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long savings = insertAccount("Savings", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    move(equity, cash, LocalDate.of(2025, 12, 1), "100.00");
    spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    move(cash, savings, LocalDate.of(2026, 1, 9), "30.00");
    spend(cash, food, LocalDate.of(2026, 2, 9), "8.00");
    ReportSpec spec =
        spec(
            List.of(Dimension.DATE),
            List.of(Dimension.ACCOUNT),
            List.of(BASE_CLOSING),
            Scope.ofTypes("asset"));

    assertThat(assertEveryFigureClosesOnItsList(spec, Set.of("2026-01"))).isPositive();
  }

  @Test
  void closingBalanceOfPersonalDebtsAndOtherDimensionsClose() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    final long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    long card = insertAccount("Card", "liability", EUR, null);
    final long food = insertAccount("Food", "expense", EUR, null);
    long doe = insertPerson("Doe");
    long doeEur = insertPersonAccount(doe, "personal.EUR", EUR);
    final long doeChf = insertPersonAccount(doe, "personal.CHF", CHF);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    move(card, cash, LocalDate.of(2025, 12, 5), "200.00");
    move(cash, doeEur, LocalDate.of(2025, 12, 6), "20.00");
    spend(cashChf, food, LocalDate.of(2026, 1, 6), "10.00");
    spend(doeChf, food, LocalDate.of(2026, 1, 9), "10.00");
    move(doeEur, cash, LocalDate.of(2026, 2, 8), "12.00");
    Scope ownAccounts = Scope.ofTypes("asset", "liability");
    String doeKey = NodeKey.PERSONAL_DEBTS + "|" + NodeKey.personKey(doe);

    ReportSpec byAccount =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            List.of(BASE_CLOSING),
            ownAccounts);
    assertThat(assertEveryFigureClosesOnItsList(byAccount, Set.of(NodeKey.PERSONAL_DEBTS, doeKey)))
        .isPositive();
    for (Dimension dimension :
        List.of(Dimension.PERSON, Dimension.CURRENCY, Dimension.ACCOUNT_TYPE)) {
      ReportSpec spec =
          spec(List.of(dimension), List.of(Dimension.DATE), List.of(BASE_CLOSING), ownAccounts);
      assertThat(assertEveryFigureClosesOnItsList(spec, AUTO)).as("%s", dimension).isPositive();
    }
  }
}
