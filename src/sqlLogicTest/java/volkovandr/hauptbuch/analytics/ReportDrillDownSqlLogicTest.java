package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.analytics.repository.NodeKey;

/**
 * SQL-logic tier (CLAUDE.md §6): the drill-down (reporting.md §12) — every cell and total a Report
 * renders opens a list whose running column closes on that very figure. The list's posting set
 * comes from the report's own grouped queries, so this boots the engine against real Postgres and
 * checks the round trip end to end: render the grid, address each figure from its position, drill
 * it, and compare. Each scenario is one of the dimension/measure shapes the engine keys
 * differently.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportDrillDownSqlLogicTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 3, 31);
  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String USD = "USD";
  private static final Measure BASE_NET = Measure.turnover(PresentationCurrency.BASE, Leg.NET);
  private static final Measure NATIVE_NET = Measure.turnover(PresentationCurrency.ACCOUNT, Leg.NET);

  private static final Scope EXPENSES = Scope.ofTypes("expense");

  /** No explicit expansion: the Report renders {@code auto} (reporting.md §9.2). */
  private static final Set<String> AUTO = null;

  @Autowired JdbcClient jdbcClient;
  @Autowired ReportEngine engine;
  @Autowired ReportDrillDown drillDown;

  @BeforeEach
  void setBaseCurrency() {
    jdbcClient.sql("update settings set base_currency = 'EUR' where settings_id = 1").update();
  }

  // ── seeding helpers ───────────────────────────────────────────────────────

  private long insertAccount(String name, String type, String currency, Long parentId) {
    return jdbcClient
        .sql(
            """
            insert into account (name, type, currency_code, parent_id)
            values (:n, :t, :c, :p)
            returning account_id
            """)
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private long insertTransaction(LocalDate date) {
    return insertTransaction(date, null);
  }

  private long insertTransaction(LocalDate date, Long payeeId) {
    return jdbcClient
        .sql(
            "insert into transaction (date, payee_id) values (:d, :payee) returning transaction_id")
        .param("d", date)
        .param("payee", payeeId)
        .query(Long.class)
        .single();
  }

  private long insertPerson(String name) {
    return jdbcClient
        .sql("insert into person (name) values (:n) returning person_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  /**
   * A person's debt leaf: an asset flagged {@code person_leaf}, owned via {@code account_owner}.
   */
  private long insertPersonAccount(long personId, String name, String currency) {
    long accountId = insertAccount(name, "asset", currency, null);
    jdbcClient
        .sql("update account set person_leaf = true where account_id = :a")
        .param("a", accountId)
        .update();
    jdbcClient
        .sql("insert into account_owner (account_id, person_id) values (:a, :p)")
        .param("a", accountId)
        .param("p", personId)
        .update();
    return accountId;
  }

  private long insertTag(String name, Long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void tag(long postingId, long tagId) {
    jdbcClient
        .sql("insert into posting_tag (posting_id, tag_id) values (:p, :t)")
        .param("p", postingId)
        .param("t", tagId)
        .update();
  }

  private long insertPayee(String name) {
    return jdbcClient
        .sql("insert into payee (name) values (:n) returning payee_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private long insertPosting(long txnId, long accountId, String amount) {
    return jdbcClient
        .sql(
            """
            insert into posting (transaction_id, account_id, amount)
            values (:t, :a, :amt)
            returning posting_id
            """)
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  private long insertPosting(long txnId, long accountId, String amount, String baseAmount) {
    long postingId = insertPosting(txnId, accountId, amount);
    jdbcClient
        .sql("update posting set base_amount = :b where posting_id = :p")
        .param("b", new BigDecimal(baseAmount))
        .param("p", postingId)
        .update();
    return postingId;
  }

  private void insertRate(String currency, LocalDate date, String rate) {
    jdbcClient
        .sql(
            "insert into exchange_rate (currency_code, date, rate, source) "
                + "values (:c, :d, :r, 'ecb')")
        .param("c", currency)
        .param("d", date)
        .param("r", new BigDecimal(rate))
        .update();
  }

  /** A two-leg purchase: {@code payer} credited, {@code category} debited; the category leg. */
  private long spend(long payer, long category, LocalDate date, String amount) {
    long txn = insertTransaction(date);
    insertPosting(txn, payer, "-" + amount);
    return insertPosting(txn, category, amount);
  }

  private static ReportSpec spec(
      List<Dimension> rows, List<Dimension> columns, List<Measure> measures, Scope scope) {
    return new ReportSpec(
        rows,
        columns,
        List.of(),
        measures,
        scope,
        List.of(),
        new DateRange(
            new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
            new RangeEndpoint.Literal(LocalDate.of(2026, 2, 28))),
        true,
        true,
        true);
  }

  /**
   * Renders {@code spec}, then drills every non-blank body cell and total and asserts two things:
   * the address a grid position yields resolves back to that very figure, and the list's running
   * column ends on it. Returns how many figures were drilled, so a scenario can prove it actually
   * exercised something.
   */
  private int assertEveryFigureClosesOnItsList(ReportSpec spec, Set<String> expanded) {
    int drilled = 0;
    for (Drilled figure : figuresOf(spec, engine.render(spec, TODAY, expanded))) {
      Measure measure = spec.measures().get(figure.address().measureIndex());
      if (!DrillDown.isDrillable(figure.cell(), measure)) {
        continue;
      }
      DrillDown list = drillDown.drill(spec, expanded, figure.address(), TODAY);
      assertSameFigure(list.cell(), figure.cell(), figure.address());
      assertThat(list.rows()).as("postings behind %s", figure.address()).isNotEmpty();
      assertSameFigure(list.rows().getLast().running(), figure.cell(), figure.address());
      drilled++;
    }
    return drilled;
  }

  /** Every body cell and every shown total of {@code grid}, each with its address. */
  private static List<Drilled> figuresOf(ReportSpec spec, ReportGrid grid) {
    List<Drilled> figures = new ArrayList<>();
    for (int row = 0; row < grid.rows().size(); row++) {
      for (int column = 0; column < grid.columns().size(); column++) {
        figures.add(
            new Drilled(
                grid.cells().get(row).get(column), CellAddress.forBody(spec, grid, row, column)));
      }
      if (spec.rowTotals()) {
        figures.add(new Drilled(grid.rowTotals().get(row), CellAddress.forRowTotal(grid, row)));
      }
    }
    for (int column = 0; column < grid.columnTotals().size(); column++) {
      figures.add(
          new Drilled(
              grid.columnTotals().get(column), CellAddress.forColumnTotal(spec, grid, column)));
    }
    if (spec.rowTotals() && spec.columnTotals()) {
      figures.add(new Drilled(grid.grandTotal(), CellAddress.forGrandTotal()));
    }
    return figures;
  }

  private static void assertSameFigure(Cell actual, Cell expected, CellAddress address) {
    if (expected instanceof Cell.Value value) {
      assertThat(actual).as("figure at %s", address).isInstanceOf(Cell.Value.class);
      Cell.Value actualValue = (Cell.Value) actual;
      assertThat(actualValue.amount())
          .as("amount at %s", address)
          .isEqualByComparingTo(value.amount());
      assertThat(actualValue.currencyCode()).isEqualTo(value.currencyCode());
      return;
    }
    assertThat(actual).as("figure at %s", address).isEqualTo(expected);
  }

  private record Drilled(Cell cell, CellAddress address) {}

  // ── scenarios ─────────────────────────────────────────────────────────────

  @Test
  void categoryByMonthCellsAndTotalsCloseOnTheirLists() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long restaurants = insertAccount("Restaurants", "expense", EUR, food);
    long transport = insertAccount("Transport", "expense", EUR, null);
    final long lunch = spend(cash, food, LocalDate.of(2026, 1, 15), "20.00");
    final long dinner = spend(cash, restaurants, LocalDate.of(2026, 1, 20), "30.00");
    spend(cash, transport, LocalDate.of(2026, 1, 21), "5.00");
    spend(cash, food, LocalDate.of(2026, 2, 3), "12.50");
    ReportSpec spec =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(BASE_NET),
            Scope.ofTypes("income", "expense"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // Food × Jan, Food × Feb, Transport × Jan, two row totals, two column totals, the grand total.
    assertThat(drilled).isEqualTo(8);
    ReportGrid grid = engine.render(spec, TODAY, AUTO);
    int foodRow = rowIndex(grid, "Food");
    DrillDown foodJanuary =
        drillDown.drill(spec, AUTO, CellAddress.forBody(spec, grid, foodRow, 0), TODAY);
    assertThat(foodJanuary.rows())
        .extracting(row -> row.posting().postingId())
        .containsExactly(lunch, dinner);
  }

  @Test
  void crossCurrencyFiguresCloseInBaseAndNativeSideBySide() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    final long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    final long cashUsd = insertAccount("Cash-USD", "asset", USD, null);
    long food = insertAccount("Food", "expense", EUR, null);
    final long foodChf = insertAccount("Food-CHF", "expense", CHF, food);
    long gifts = insertAccount("Gifts", "expense", CHF, null);
    final long giftsUsd = insertAccount("Gifts-USD", "expense", USD, gifts);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90"); // carries forward into January
    insertRate(CHF, LocalDate.of(2026, 2, 1), "0.95");
    spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    spend(cashChf, foodChf, LocalDate.of(2026, 1, 10), "10.00");
    spend(cashChf, foodChf, LocalDate.of(2026, 2, 10), "30.00");
    long frozen = insertTransaction(LocalDate.of(2026, 2, 12));
    insertPosting(frozen, cashChf, "-20.00", "-18.50");
    insertPosting(frozen, foodChf, "20.00", "18.50"); // frozen, never revalued at 0.95
    spend(cashChf, gifts, LocalDate.of(2026, 1, 20), "8.00");
    spend(cashUsd, giftsUsd, LocalDate.of(2026, 1, 25), "4.00"); // USD has no rate at all
    ReportSpec spec =
        spec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(BASE_NET, NATIVE_NET),
            Scope.ofTypes("expense"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // Food × {Jan, Feb} and Gifts × Jan, each in base and native: base Gifts × Jan is — for the
    // missing USD rate and native Food × Jan and Gifts × Jan are — for spanning two currencies,
    // yet all six still list their postings. The row totals and the grand total add two measures
    // and have no posting set; the column totals of each measure do.
    assertThat(drilled).isEqualTo(6 + 4);
  }

  @Test
  void debitAndCreditLegsOfOwnAccountsCloseWithTheLiabilityFlip() {
    final long bank = insertAccount("BankAaa-EUR", "asset", EUR, null);
    long card = insertAccount("Card", "liability", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    final long salary = insertAccount("Salary", "income", EUR, null);
    spend(card, food, LocalDate.of(2026, 1, 5), "40.00");
    long refund = insertTransaction(LocalDate.of(2026, 1, 9));
    insertPosting(refund, food, "-15.00");
    insertPosting(refund, card, "15.00");
    long pay = insertTransaction(LocalDate.of(2026, 1, 25));
    insertPosting(pay, salary, "-1000.00");
    insertPosting(pay, bank, "1000.00");
    long settle = insertTransaction(LocalDate.of(2026, 2, 1));
    insertPosting(settle, bank, "-25.00");
    insertPosting(settle, card, "25.00");
    ReportSpec spec =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            List.of(
                Measure.turnover(PresentationCurrency.BASE, Leg.DEBITS),
                Measure.turnover(PresentationCurrency.BASE, Leg.CREDITS)),
            Scope.ofTypes("asset", "liability"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // BankAaa: Jan debits, Feb credits; Card: Jan debits and credits, Feb debits — five cells,
    // then the column totals of the four non-empty columns.
    assertThat(drilled).isEqualTo(5 + 4);
  }

  @Test
  void payeeCellsCloseIncludingTheNoPayeeRowAndBothCounts() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    final long savings = insertAccount("Savings", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long shop = insertPayee("ShopAaa");
    long split = insertTransaction(LocalDate.of(2026, 1, 5), shop);
    insertPosting(split, cash, "-30.00");
    insertPosting(split, food, "20.00");
    insertPosting(split, food, "10.00");
    long again = insertTransaction(LocalDate.of(2026, 1, 7), shop);
    insertPosting(again, cash, "-5.00");
    insertPosting(again, food, "5.00");
    long transfer = insertTransaction(LocalDate.of(2026, 1, 9));
    insertPosting(transfer, cash, "-50.00");
    insertPosting(transfer, savings, "50.00");
    ReportSpec spec =
        spec(
            List.of(Dimension.PAYEE),
            List.of(),
            List.of(Measure.countPostings(), Measure.countTransactions(), NATIVE_NET),
            Scope.ofTypes("asset", "expense"));

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // ShopAaa and (No payee), three measures each; the three column totals.
    assertThat(drilled).isEqualTo(6 + 3);
  }

  @Test
  void tagRowsCloseIncludingUnspecifiedAndRefuseTheCrossTagTotal() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long hotel = insertAccount("Hotel", "expense", EUR, null);
    long trips = insertTag("Trips", null);
    long rome = insertTag("Rome", trips);
    final long paris = insertTag("Paris", trips);
    long onTrip = spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    tag(onTrip, trips);
    long inRome = spend(cash, hotel, LocalDate.of(2026, 1, 6), "100.00");
    tag(inRome, rome);
    tag(inRome, trips); // on the parent and a child at once — once under Trips, once in each row
    long romeAndParis = spend(cash, food, LocalDate.of(2026, 1, 8), "7.00");
    tag(romeAndParis, rome);
    tag(romeAndParis, paris);
    ReportSpec spec =
        spec(List.of(Dimension.TAG), List.of(Dimension.DATE), List.of(BASE_NET), EXPENSES);

    int drilled = assertEveryFigureClosesOnItsList(spec, Set.of(String.valueOf(trips)));

    // Trips, (unspecified), Paris, Rome × Jan, and their four row totals; the column and grand
    // totals add overlapping tags and have no posting set.
    assertThat(drilled).isEqualTo(4 + 4);
    ReportGrid grid = engine.render(spec, TODAY, Set.of(String.valueOf(trips)));
    DrillDown unspecified =
        drillDown.drill(
            spec,
            Set.of(String.valueOf(trips)),
            CellAddress.forBody(spec, grid, rowIndex(grid, "(unspecified)"), 0),
            TODAY);
    assertThat(unspecified.rows())
        .extracting(row -> row.posting().postingId())
        .containsExactly(onTrip, inRome);
    DrillDown crossTag =
        drillDown.drill(
            spec, Set.of(String.valueOf(trips)), CellAddress.forColumnTotal(spec, grid, 0), TODAY);
    assertThat(crossTag.rows()).isEmpty();
  }

  @Test
  void nestedCategoryRowsUnderAnExpandedTagClose() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long hotel = insertAccount("Hotel", "expense", EUR, null);
    long trips = insertTag("Trips", null);
    long work = insertTag("Work", null);
    tag(spend(cash, food, LocalDate.of(2026, 1, 5), "20.00"), trips);
    tag(spend(cash, hotel, LocalDate.of(2026, 1, 6), "100.00"), trips);
    tag(spend(cash, food, LocalDate.of(2026, 2, 6), "9.00"), work);
    spend(cash, food, LocalDate.of(2026, 2, 7), "3.00"); // untagged: in no tag row at all
    ReportSpec spec =
        spec(
            List.of(Dimension.TAG, Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(BASE_NET),
            EXPENSES);

    int drilled = assertEveryFigureClosesOnItsList(spec, Set.of(String.valueOf(trips)));

    // Trips (Jan) with Food and Hotel beneath it, Work (Feb) collapsed; each row's total.
    assertThat(drilled).isEqualTo(4 + 4);
  }

  @Test
  void personalDebtsExpandedToPersonAndLeavesClose() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long doe = insertPerson("Doe");
    final long doeEur = insertPersonAccount(doe, "personal.EUR", EUR);
    final long doeChf = insertPersonAccount(doe, "personal.CHF", CHF);
    long max = insertPerson("Max");
    final long maxEur = insertPersonAccount(max, "personal.EUR", EUR);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    long lent = insertTransaction(LocalDate.of(2026, 1, 5)); // paid for Doe's half
    insertPosting(lent, cash, "-40.00");
    insertPosting(lent, food, "20.00");
    insertPosting(lent, doeEur, "20.00");
    spend(doeChf, food, LocalDate.of(2026, 1, 9), "10.00"); // Doe paid for me, in CHF
    spend(cash, maxEur, LocalDate.of(2026, 2, 2), "15.00");
    ReportSpec spec =
        spec(
            List.of(Dimension.ACCOUNT),
            List.of(Dimension.DATE),
            List.of(BASE_NET),
            Scope.ofTypes("asset"));
    String doeKey = NodeKey.PERSONAL_DEBTS + "|" + NodeKey.personKey(doe);

    int drilled = assertEveryFigureClosesOnItsList(spec, Set.of(NodeKey.PERSONAL_DEBTS, doeKey));

    // Rows: Cash, Personal debts, Doe, Doe's EUR and CHF leaves, Max. Cells: Cash and Personal
    // debts in both months, Doe and each of Doe's leaves in January, Max in February — eight;
    // then six row totals, two column totals and the grand total.
    assertThat(drilled).isEqualTo(8 + 6 + 2 + 1);
  }

  @Test
  void personCurrencyAndAccountTypeDimensionsClose() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long cashChf = insertAccount("Cash-CHF", "asset", CHF, null);
    final long card = insertAccount("Card", "liability", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long doe = insertPerson("Doe");
    final long doeEur = insertPersonAccount(doe, "personal.EUR", EUR);
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90");
    spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    spend(cashChf, food, LocalDate.of(2026, 1, 6), "10.00");
    spend(card, food, LocalDate.of(2026, 2, 7), "30.00");
    spend(cash, doeEur, LocalDate.of(2026, 2, 8), "12.00");
    Scope ownAccounts = Scope.ofTypes("asset", "liability");

    for (Dimension dimension :
        List.of(Dimension.PERSON, Dimension.CURRENCY, Dimension.ACCOUNT_TYPE)) {
      ReportSpec spec =
          spec(List.of(dimension), List.of(Dimension.DATE), List.of(BASE_NET), ownAccounts);
      assertThat(assertEveryFigureClosesOnItsList(spec, AUTO)).as("%s", dimension).isPositive();
    }
  }

  @Test
  void dateRowsWithOneMonthExpandedIntoDaysClose() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long transport = insertAccount("Transport", "expense", EUR, null);
    spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    spend(cash, food, LocalDate.of(2026, 1, 5), "4.00");
    spend(cash, transport, LocalDate.of(2026, 1, 9), "3.00");
    spend(cash, food, LocalDate.of(2026, 2, 9), "8.00");
    ReportSpec spec =
        spec(List.of(Dimension.DATE), List.of(Dimension.CATEGORY), List.of(BASE_NET), EXPENSES);

    int drilled = assertEveryFigureClosesOnItsList(spec, Set.of("2026-01"));

    // Jan (Food, Transport), its days 5th (Food) and 9th (Transport), Feb (Food); a row total for
    // each of those four rows; two column totals over the top-level months; the grand total.
    assertThat(drilled).isEqualTo(5 + 4 + 2 + 1);
  }

  @Test
  void promotedFilterNodeAndItsFormerParentCloseSeparately() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long restaurants = insertAccount("Restaurants", "expense", EUR, food);
    long groceries = insertAccount("Groceries", "expense", EUR, food);
    long dinner = spend(cash, restaurants, LocalDate.of(2026, 1, 5), "30.00");
    spend(cash, groceries, LocalDate.of(2026, 1, 6), "12.00");
    ReportSpec spec =
        new ReportSpec(
            List.of(Dimension.CATEGORY),
            List.of(Dimension.DATE),
            List.of(),
            List.of(BASE_NET),
            EXPENSES,
            List.of(
                new ReportFilter(
                    FilterField.CATEGORY,
                    FilterLevel.POSTING,
                    FilterOperator.IS_ONE_OF,
                    List.of(String.valueOf(restaurants), String.valueOf(groceries)))),
            new DateRange(
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 1)),
                new RangeEndpoint.Literal(LocalDate.of(2026, 1, 31))),
            true,
            true,
            true);

    int drilled = assertEveryFigureClosesOnItsList(spec, AUTO);

    // Each ticked node is its own top-level row: two cells, two row totals, one column total, the
    // grand total.
    assertThat(drilled).isEqualTo(2 + 2 + 1 + 1);
    ReportGrid grid = engine.render(spec, TODAY, AUTO);
    DrillDown restaurantsRow =
        drillDown.drill(
            spec,
            AUTO,
            CellAddress.forBody(spec, grid, rowIndex(grid, "Food:Restaurants"), 0),
            TODAY);
    assertThat(restaurantsRow.rows())
        .extracting(row -> row.posting().postingId())
        .containsExactly(dinner);
  }

  @Test
  void pendingReviewTransactionsAreListedExactlyWhenTheScopeCountsThem() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long confirmed = spend(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    long pending = spend(cash, food, LocalDate.of(2026, 1, 6), "5.00");
    jdbcClient
        .sql(
            "update transaction set lifecycle = 'pending_review' where transaction_id ="
                + " (select transaction_id from posting where posting_id = :p)")
        .param("p", pending)
        .update();

    for (boolean includePending : List.of(false, true)) {
      ReportSpec spec =
          spec(
              List.of(Dimension.CATEGORY),
              List.of(Dimension.DATE),
              List.of(BASE_NET),
              expensesCounting(includePending));
      assertThat(assertEveryFigureClosesOnItsList(spec, AUTO)).isPositive();
      ReportGrid grid = engine.render(spec, TODAY, AUTO);
      DrillDown foodJanuary =
          drillDown.drill(spec, AUTO, CellAddress.forBody(spec, grid, 0, 0), TODAY);
      assertThat(foodJanuary.rows())
          .extracting(row -> row.posting().postingId())
          .containsExactlyElementsOf(
              includePending ? List.of(confirmed, pending) : List.of(confirmed));
    }
  }

  private static Scope expensesCounting(boolean pendingReview) {
    return new Scope(Set.of("expense"), true, pendingReview);
  }

  private static int rowIndex(ReportGrid grid, String label) {
    for (int i = 0; i < grid.rows().size(); i++) {
      if (grid.rows().get(i).label().equals(label)) {
        return i;
      }
    }
    throw new AssertionError("No row " + label + " in " + grid.rows());
  }
}
