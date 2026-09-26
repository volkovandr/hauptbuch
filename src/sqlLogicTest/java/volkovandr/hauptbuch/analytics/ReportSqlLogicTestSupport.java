package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;

/**
 * What the drill-down's SQL-logic tests (reporting.md §12) share: seeding a crafted ledger, and the
 * round trip every scenario runs — render the grid, address each figure from its position, drill
 * it, and check the list's running column ends on that very figure. The list's posting set comes
 * from the Report's own grouped queries, so this boots the engine against real Postgres.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
abstract class ReportSqlLogicTestSupport {

  static final LocalDate TODAY = LocalDate.of(2026, 3, 31);
  static final String EUR = "EUR";
  static final String CHF = "CHF";
  static final String USD = "USD";
  static final Measure BASE_NET = Measure.turnover(PresentationCurrency.BASE, Leg.NET);
  static final Measure NATIVE_NET = Measure.turnover(PresentationCurrency.ACCOUNT, Leg.NET);
  static final Measure BASE_CLOSING = Measure.closingBalance(PresentationCurrency.BASE);
  static final Measure NATIVE_CLOSING = Measure.closingBalance(PresentationCurrency.ACCOUNT);

  static final Scope EXPENSES = Scope.ofTypes("expense");

  /** No explicit expansion: the Report renders {@code auto} (reporting.md §9.2). */
  static final Set<String> AUTO = null;

  @Autowired JdbcClient jdbcClient;
  @Autowired ReportEngine engine;
  @Autowired ReportDrillDown drillDown;

  @BeforeEach
  void setBaseCurrency() {
    jdbcClient.sql("update settings set base_currency = 'EUR' where settings_id = 1").update();
  }

  // ── seeding helpers ───────────────────────────────────────────────────────

  long insertAccount(String name, String type, String currency, Long parentId) {
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

  long insertTransaction(LocalDate date) {
    return insertTransaction(date, null);
  }

  long insertTransaction(LocalDate date, Long payeeId) {
    return jdbcClient
        .sql(
            "insert into transaction (date, payee_id) values (:d, :payee) returning transaction_id")
        .param("d", date)
        .param("payee", payeeId)
        .query(Long.class)
        .single();
  }

  long insertPerson(String name) {
    return jdbcClient
        .sql("insert into person (name) values (:n) returning person_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  /**
   * A person's debt leaf: an asset flagged {@code person_leaf}, owned via {@code account_owner}.
   */
  long insertPersonAccount(long personId, String name, String currency) {
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

  long insertTag(String name, Long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  void tag(long postingId, long tagId) {
    jdbcClient
        .sql("insert into posting_tag (posting_id, tag_id) values (:p, :t)")
        .param("p", postingId)
        .param("t", tagId)
        .update();
  }

  long insertPayee(String name) {
    return jdbcClient
        .sql("insert into payee (name) values (:n) returning payee_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  long insertPosting(long txnId, long accountId, String amount) {
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

  long insertPosting(long txnId, long accountId, String amount, String baseAmount) {
    long postingId = insertPosting(txnId, accountId, amount);
    jdbcClient
        .sql("update posting set base_amount = :b where posting_id = :p")
        .param("b", new BigDecimal(baseAmount))
        .param("p", postingId)
        .update();
    return postingId;
  }

  void insertRate(String currency, LocalDate date, String rate) {
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
  long spend(long payer, long category, LocalDate date, String amount) {
    long txn = insertTransaction(date);
    insertPosting(txn, payer, "-" + amount);
    return insertPosting(txn, category, amount);
  }

  static ReportSpec spec(
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
   * column — from zero, or from a closing balance's opening line — ends on it. Returns how many
   * figures were drilled, so a scenario can prove it actually exercised something.
   */
  int assertEveryFigureClosesOnItsList(ReportSpec spec, Set<String> expanded) {
    int drilled = 0;
    for (Drilled figure : figuresOf(spec, engine.render(spec, TODAY, expanded))) {
      Measure measure = spec.measures().get(figure.address().measureIndex());
      if (!DrillDown.isDrillable(figure.cell())) {
        continue;
      }
      DrillDown list = drillDown.drill(spec, expanded, figure.address(), TODAY);
      assertSameFigure(list.cell(), figure.cell(), figure.address());
      if (measure.kind() == MeasureKind.CLOSING_BALANCE) {
        assertThat(list.opening()).as("opening line of %s", figure.address()).isNotNull();
      } else {
        assertThat(list.rows()).as("postings behind %s", figure.address()).isNotEmpty();
      }
      assertSameFigure(list.closing(), figure.cell(), figure.address());
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

  static void assertSameFigure(Cell actual, Cell expected, CellAddress address) {
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

  /** Money moved between two accounts: {@code from} credited, {@code to} debited; {@code to}'s. */
  long move(long from, long to, LocalDate date, String amount) {
    long txn = insertTransaction(date);
    insertPosting(txn, from, "-" + amount);
    return insertPosting(txn, to, amount);
  }

  static int rowIndex(ReportGrid grid, String label) {
    for (int i = 0; i < grid.rows().size(); i++) {
      if (grid.rows().get(i).label().equals(label)) {
        return i;
      }
    }
    throw new AssertionError("No row " + label + " in " + grid.rows());
  }
}
