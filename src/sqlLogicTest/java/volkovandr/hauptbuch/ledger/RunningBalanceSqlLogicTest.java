package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.SqlLogicSchema;

/**
 * SQL-logic tier (plan §1.5): the per-account running-balance query (data-model §9) — a windowed
 * {@code sum} over a leaf account's postings in date order. This logic lives in the SQL and cannot
 * be mocked, so it is tested against real Postgres (the window function is one of the reasons the
 * suite refuses H2 — tech-stack §2.5).
 *
 * <p>The load-bearing case is the <strong>backdated insert</strong> (data-model §8 invariant 5): a
 * transaction inserted with an earlier date must correct the running balances of every row above
 * it. Because the balance is <em>derived</em> on read (never materialized), the corrected totals
 * fall out for free — this test pins that behaviour so a future "optimisation" to stored balances
 * cannot silently break it.
 *
 * <p><strong>TDD-ahead:</strong> unlike the rest of this suite (which now boots Spring and
 * exercises the SQL that lives in a repository), this running-balance query is still a
 * <em>test-owned</em> string constant — no production repository runs it yet. That is deliberate:
 * the SQL is written ahead of the balances stage that will consume it (CLAUDE.md §6 TDD-for-SQL).
 * When that stage lands, the query moves into a repository method and this test rebinds to it (like
 * {@link volkovandr.hauptbuch.accounts.AccountTreeSqlLogicTest} does for the recursive walkers).
 * Until then it uses raw JDBC via {@link SqlLogicSchema}.
 */
class RunningBalanceSqlLogicTest {

  private static final String HUNDRED = "100.00";

  /**
   * Per-account running balance: each posting carrying the cumulative native balance of its account
   * up to and including that posting, in {@code (date, transaction_id, posting_id)} order. The
   * deterministic tiebreak keeps same-day postings stably ordered. Scoped to live transactions.
   */
  private static final String RUNNING_BALANCE =
      """
      select p.posting_id,
             t.date,
             p.amount,
             sum(p.amount) over (
               partition by p.account_id
               order by t.date, p.transaction_id, p.posting_id
               rows between unbounded preceding and current row
             ) as running_balance
      from posting p
      join transaction t on p.transaction_id = t.transaction_id
      where p.account_id = ? and t.deleted_at is null
      order by t.date, p.transaction_id, p.posting_id
      """;

  private TestLedger ledger;
  private long account;

  @BeforeEach
  void setUp() throws SQLException {
    ledger = new TestLedger(SqlLogicSchema.connection());
    account = ledger.insertAccount("Cash", "asset", "EUR", null);
  }

  @AfterEach
  void tearDown() throws SQLException {
    ledger.close();
  }

  /**
   * Insert a one-legged posting dated {@code date} for {@code amount} on the test account,
   * returning the new transaction id.
   */
  private long post(String date, String amount) throws SQLException {
    long txnId = ledger.insertTransaction(date);
    ledger.insertPosting(txnId, account, new BigDecimal(amount), null);
    return txnId;
  }

  private List<BigDecimal> runningBalances() throws SQLException {
    List<BigDecimal> balances = new ArrayList<>();
    try (PreparedStatement ps = ledger.connection().prepareStatement(RUNNING_BALANCE)) {
      ps.setLong(1, account);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          balances.add(rs.getBigDecimal("running_balance"));
        }
      }
    }
    return balances;
  }

  @Test
  void accumulatesInDateOrder() throws SQLException {
    post("2026-01-01", HUNDRED);
    post("2026-01-05", "-30.00");
    post("2026-01-10", "50.00");

    // Compare by value (compareTo), not equals — numeric(19,4) returns scale-4 BigDecimals.
    assertThat(runningBalances())
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(
            new BigDecimal(HUNDRED), new BigDecimal("70.00"), new BigDecimal("120.00"));
  }

  @Test
  void backdatedInsertCorrectsTheBalancesOfRowsAboveIt() throws SQLException {
    post("2026-01-01", HUNDRED);
    post("2026-01-10", "50.00");
    // Running balances so far: 100, 150.
    assertThat(runningBalances())
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal(HUNDRED), new BigDecimal("150.00"));

    // A transaction surfaces dated 2026-01-05 — between the two existing rows.
    post("2026-01-05", "20.00");

    // The Jan-10 row's running balance is corrected (was 150, now 170); the new row slots in at
    // 120.
    assertThat(runningBalances())
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(
            new BigDecimal(HUNDRED), new BigDecimal("120.00"), new BigDecimal("170.00"));
  }

  @Test
  void softDeletedPostingsAreExcludedFromTheRunningBalance() throws SQLException {
    post("2026-01-01", HUNDRED);
    long second = post("2026-01-05", "-30.00");
    ledger.softDeleteTransaction(second);

    assertThat(runningBalances())
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal(HUNDRED));
  }
}
