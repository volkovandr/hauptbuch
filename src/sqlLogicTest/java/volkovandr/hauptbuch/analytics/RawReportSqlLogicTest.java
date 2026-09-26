package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SQL-logic tier (CLAUDE.md §6): a Report's raw grid (reporting.md §13) — every hierarchy on either
 * axis fully expanded to its leaves, each labelled by its path, with no parent rows and no totals,
 * fetched at leaf grain. The leaves are cross-checked against the grid the Report shows, so raw and
 * "as shown" cannot disagree about a number.
 */
class RawReportSqlLogicTest extends ReportSqlLogicTestSupport {

  private static final Scope ASSETS = Scope.ofTypes("asset");

  private static List<String> labels(ReportGrid grid) {
    return grid.rows().stream().map(AxisNode::label).toList();
  }

  private static Cell cell(ReportGrid grid, String row, int column) {
    return grid.cells().get(rowIndex(grid, row)).get(column);
  }

  private static Cell.Value eur(String amount) {
    return new Cell.Value(new BigDecimal(amount), EUR);
  }

  @Test
  void listsEveryAccountLeafByItsPathWithoutParentsOrTotals() {
    long equity = insertAccount("Opening", "equity", EUR, null);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long cashEur = insertAccount("Cash-EUR", "asset", EUR, cash);
    long cashUsd = insertAccount("Cash-USD", "asset", USD, cash);
    final long savings = insertAccount("Savings", "asset", EUR, null);
    insertRate(USD, LocalDate.of(2025, 12, 1), "0.90");
    move(equity, cashEur, LocalDate.of(2026, 1, 5), "20.00");
    move(equity, cashUsd, LocalDate.of(2026, 1, 6), "10.00");
    move(cashEur, savings, LocalDate.of(2026, 2, 7), "5.00");
    ReportSpec spec =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            List.of(BASE_NET, NATIVE_NET),
            ASSETS);

    ReportGrid raw = engine.renderRaw(spec, TODAY);

    assertThat(labels(raw)).containsExactly("Cash:Cash-EUR", "Cash:Cash-USD", "Savings");
    assertThat(raw.rowTotals()).isEmpty();
    assertThat(raw.columnTotals()).isEmpty();
    assertSameFigure(cell(raw, "Cash:Cash-USD", 0), eur("9.00"), null);
    assertSameFigure(
        cell(raw, "Cash:Cash-USD", 1), new Cell.Value(new BigDecimal("10.00"), USD), null);
    assertSameFigure(cell(raw, "Savings", 2), eur("5.00"), null);
  }

  @Test
  void leavesAddUpToTheParentTheReportShows() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    final long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long restaurants = insertAccount("Restaurants", "expense", EUR, food);
    long groceries = insertAccount("Groceries", "expense", EUR, food);
    long groceriesEur = insertAccount("Groceries-EUR", "expense", EUR, groceries);
    final long groceriesChf = insertAccount("Groceries-CHF", "expense", CHF, groceries);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    spend(cash, restaurants, LocalDate.of(2026, 1, 5), "30.00");
    spend(cash, groceriesEur, LocalDate.of(2026, 1, 6), "12.00");
    spend(cashChf, groceriesChf, LocalDate.of(2026, 1, 7), "10.00");
    ReportSpec spec =
        spec(List.of(Dimension.CATEGORY), List.of(Dimension.DATE), List.of(BASE_NET), EXPENSES);

    ReportGrid raw = engine.renderRaw(spec, TODAY);
    ReportGrid shown = engine.render(spec, TODAY, AUTO);

    assertThat(labels(raw))
        .containsExactly(
            "Food:Groceries:Groceries-CHF", "Food:Groceries:Groceries-EUR", "Food:Restaurants");
    BigDecimal leaves =
        raw.cells().stream()
            .map(row -> ((Cell.Value) row.get(0)).amount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertSameFigure(cell(shown, "Food", 0), new Cell.Value(leaves, EUR), null);
  }

  @Test
  void tagRowsAreEachPostingsOwnTagSoTheTagsOwnPostingsStayUnderIt() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long trips = insertTag("Trips", null);
    long rome = insertTag("Rome", trips);
    tag(spend(cash, food, LocalDate.of(2026, 1, 5), "20.00"), trips);
    tag(spend(cash, food, LocalDate.of(2026, 1, 6), "7.00"), rome);
    long both = spend(cash, food, LocalDate.of(2026, 1, 8), "3.00");
    tag(both, trips);
    tag(both, rome);
    spend(cash, food, LocalDate.of(2026, 1, 9), "100.00"); // untagged: in no tag row
    ReportSpec spec =
        spec(
            List.of(Dimension.TAG),
            List.of(Dimension.DATE),
            List.of(BASE_NET, Measure.countTransactions()),
            EXPENSES);

    ReportGrid raw = engine.renderRaw(spec, TODAY);

    assertThat(labels(raw)).containsExactly("Trips", "Trips:Rome");
    assertSameFigure(cell(raw, "Trips", 0), eur("23.00"), null);
    assertThat(cell(raw, "Trips", 1)).isEqualTo(new Cell.Count(2));
    assertSameFigure(cell(raw, "Trips:Rome", 0), eur("10.00"), null);
  }

  @Test
  void closingBalanceLeavesAreValuedAtEachPeriodEnd() {
    long equity = insertAccount("Opening", "equity", EUR, null);
    long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    long doe = insertPerson("Doe");
    final long doeEur = insertPersonAccount(doe, "personal.EUR", EUR);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    insertRate(CHF, LocalDate.of(2026, 2, 15), "0.95");
    move(equity, cashChf, LocalDate.of(2025, 12, 1), "100.00");
    move(equity, doeEur, LocalDate.of(2026, 2, 2), "12.00");
    ReportSpec spec =
        spec(List.of(Dimension.ACCOUNT), List.of(Dimension.DATE), List.of(BASE_CLOSING), ASSETS);

    ReportGrid raw = engine.renderRaw(spec, TODAY);

    assertThat(labels(raw)).containsExactly("Cash-CHF", "Personal debts:Doe:EUR");
    assertSameFigure(cell(raw, "Cash-CHF", 0), eur("90.00"), null);
    assertSameFigure(cell(raw, "Cash-CHF", 1), eur("95.00"), null);
    assertSameFigure(cell(raw, "Personal debts:Doe:EUR", 1), eur("12.00"), null);
  }

  @Test
  void nestedDimensionsJoinBothLeafPaths() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long lunch = insertAccount("Lunch", "expense", EUR, food);
    long hotel = insertAccount("Hotel", "expense", EUR, null);
    long trips = insertTag("Trips", null);
    tag(spend(cash, lunch, LocalDate.of(2026, 1, 5), "20.00"), trips);
    tag(spend(cash, hotel, LocalDate.of(2026, 1, 6), "100.00"), trips);
    ReportSpec spec =
        spec(
            List.of(Dimension.TAG, Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(BASE_NET),
            EXPENSES);

    ReportGrid raw = engine.renderRaw(spec, TODAY);

    assertThat(labels(raw)).containsExactly("Trips / Food:Lunch", "Trips / Hotel");
    assertSameFigure(cell(raw, "Trips / Hotel", 0), eur("100.00"), null);
  }

  @Test
  void dateRowsStayAtTheLadderRungWithLeafColumns() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long lunch = insertAccount("Lunch", "expense", EUR, food);
    spend(cash, lunch, LocalDate.of(2026, 1, 10), "2.00");
    long payee = insertPayee("ShopAaa");
    long txn = insertTransaction(LocalDate.of(2026, 2, 3), payee);
    insertPosting(txn, cash, "-8.00");
    insertPosting(txn, lunch, "8.00");
    ReportSpec byCategory =
        spec(List.of(Dimension.DATE), List.of(Dimension.CATEGORY), List.of(BASE_NET), EXPENSES);
    final ReportSpec byPayee =
        spec(List.of(Dimension.DATE), List.of(Dimension.PAYEE), List.of(BASE_NET), EXPENSES);

    ReportGrid raw = engine.renderRaw(byCategory, TODAY);

    assertThat(raw.rows()).extracting(AxisNode::key).containsExactly("2026-01", "2026-02");
    assertThat(raw.columns()).extracting(AxisNode::label).containsExactly("Food:Lunch");
    assertSameFigure(raw.cells().get(1).get(0), eur("8.00"), null);
    assertThat(engine.renderRaw(byPayee, TODAY).columns())
        .extracting(AxisNode::label)
        .containsExactly("(No payee)", "ShopAaa");
  }

  @Test
  void refusedSpecStaysRefused() {
    ReportSpec tagBalance =
        spec(List.of(Dimension.TAG), List.of(Dimension.DATE), List.of(BASE_CLOSING), ASSETS);

    assertThat(engine.renderRaw(tagBalance, TODAY).refusalMessage()).isNotNull();
  }

  @Test
  void tagFilterOnBookedAmountsKeepsOtherTagsOfTheSamePostingsOut() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long trips = insertTag("Trips", null);
    long rome = insertTag("Rome", trips);
    long work = insertTag("Work", null);
    long both = spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    tag(both, rome);
    tag(both, work); // on screen, Trips alone is shown: Work is not ticked
    ReportSpec tripsOnly =
        new ReportSpec(
            List.of(Dimension.TAG),
            List.of(Dimension.DATE),
            List.of(),
            List.of(BASE_NET),
            EXPENSES,
            List.of(
                new ReportFilter(
                    FilterField.TAG,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(trips)))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            true,
            true,
            true);

    assertThat(labels(engine.renderRaw(tripsOnly, TODAY))).containsExactly("Trips:Rome");
  }

  @Test
  void personLeavesAreTheOwnersOfDebtLeaves() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long doe = insertPerson("Doe");
    long doeEur = insertPersonAccount(doe, "personal.EUR", EUR);
    long doeChf = insertPersonAccount(doe, "personal.CHF", CHF);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    move(cash, doeEur, LocalDate.of(2026, 1, 5), "20.00");
    move(cash, doeChf, LocalDate.of(2026, 1, 6), "10.00");
    ReportSpec spec =
        spec(List.of(Dimension.PERSON), List.of(Dimension.DATE), List.of(BASE_NET), ASSETS);

    ReportGrid raw = engine.renderRaw(spec, TODAY);

    assertThat(labels(raw)).containsExactly("Doe");
    assertSameFigure(cell(raw, "Doe", 0), eur("29.00"), null);
  }
}
