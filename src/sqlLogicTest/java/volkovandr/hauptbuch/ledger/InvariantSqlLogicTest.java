package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.SqlLogicSchema;

/**
 * SQL-logic tier (plan §1.5): the double-entry integrity invariants (data-model §8), written as
 * native SQL and run against real Postgres. These queries <em>are</em> the living spec — each finds
 * the transactions that violate an invariant, and the test crafts both a clean book (no violations)
 * and a deliberately broken row (one violation) so the query is shown to fire only when it should.
 *
 * <p>Each test runs in a rolled-back transaction (autocommit off) so the crafted data never leaks
 * into the reused container.
 */
class InvariantSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";
  private static final String JUNE_1 = "2026-06-01";
  private static final String FOOD_EUR = "Food EUR";
  private static final String CASH = "Cash";
  private static final String MINUS_5 = "-5.00";
  private static final String NINETY_FIVE = "95.00";

  /**
   * Sum-to-zero, conditional on currency mix (data-model §8 invariant 1). A live transaction is in
   * violation when it is single-currency and its native amounts do not sum to zero, or it is
   * cross-currency and either a leg lacks a frozen base_amount or the base amounts do not sum to
   * zero. Counts the offending transactions (zero on a correct book).
   */
  private static final String SUM_TO_ZERO_VIOLATIONS =
      """
      with live as (
        select p.transaction_id, p.amount, p.base_amount, a.currency_code
        from posting p
        join transaction t on p.transaction_id = t.transaction_id
        join account a on p.account_id = a.account_id
        where t.deleted_at is null
      ),
      per_txn as (
        select transaction_id,
               count(distinct currency_code)      as currencies,
               sum(amount)                         as native_sum,
               sum(base_amount)                    as base_sum,
               count(*) filter (where base_amount is null) as missing_base
        from live
        group by transaction_id
      )
      select count(*)
      from per_txn
      where (currencies = 1 and native_sum <> 0)
         or (currencies > 1 and (missing_base > 0 or base_sum <> 0 or base_sum is null))
      """;

  /**
   * Leaves-only for accounts (data-model §8 invariant 2): no posting may reference an account that
   * is some other account's parent. Counts the offending postings (zero on a correct book).
   */
  private static final String LEAVES_ONLY_VIOLATIONS =
      """
      select count(*)
      from posting p
      where p.account_id in (select parent_id from account where parent_id is not null)
      """;

  private TestLedger ledger;

  @BeforeEach
  void setUp() throws SQLException {
    ledger = new TestLedger(SqlLogicSchema.connection());
  }

  @AfterEach
  void tearDown() throws SQLException {
    ledger.close();
  }

  private long insertAccount(String name, String type, String currency) throws SQLException {
    return ledger.insertAccount(name, type, currency, null);
  }

  private long insertChildAccount(String name, String type, String currency, long parentId)
      throws SQLException {
    return ledger.insertAccount(name, type, currency, parentId);
  }

  private long insertTransaction(String date) throws SQLException {
    return ledger.insertTransaction(date);
  }

  private void insertPosting(long txnId, long accountId, String amount, String baseAmount)
      throws SQLException {
    ledger.insertPosting(
        txnId,
        accountId,
        new BigDecimal(amount),
        baseAmount == null ? null : new BigDecimal(baseAmount));
  }

  /** Count the rows a (constant) violation query returns. */
  private long countRows(String countQuery) throws SQLException {
    try (PreparedStatement ps = ledger.connection().prepareStatement(countQuery);
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void balancedSingleCurrencyTransactionPassesSumToZero() throws SQLException {
    long cash = insertAccount(CASH, ASSET, EUR);
    long food = insertAccount(FOOD_EUR, EXPENSE, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cash, MINUS_5, null);
    insertPosting(txn, food, "5.00", null);

    assertThat(countRows(SUM_TO_ZERO_VIOLATIONS)).isZero();
  }

  @Test
  void unbalancedSingleCurrencyTransactionViolatesSumToZero() throws SQLException {
    long cash = insertAccount(CASH, ASSET, EUR);
    long food = insertAccount(FOOD_EUR, EXPENSE, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cash, MINUS_5, null);
    insertPosting(txn, food, "4.00", null); // does not balance

    assertThat(countRows(SUM_TO_ZERO_VIOLATIONS)).isEqualTo(1L);
  }

  @Test
  void crossCurrencyTransactionBalancesInBaseNotNative() throws SQLException {
    long cardChf = insertAccount("Card CHF", ASSET, CHF);
    long cashEur = insertAccount("Cash EUR", ASSET, EUR);
    long txn = insertTransaction(JUNE_1);
    // Native legs (−100 CHF, +95 EUR) do NOT sum to zero; base legs (−95, +95) do.
    insertPosting(txn, cardChf, "-100.00", "-95.00");
    insertPosting(txn, cashEur, NINETY_FIVE, NINETY_FIVE);

    assertThat(countRows(SUM_TO_ZERO_VIOLATIONS)).isZero();
  }

  @Test
  void crossCurrencyLegMissingBaseAmountViolatesSumToZero() throws SQLException {
    long cardChf = insertAccount("Card CHF", ASSET, CHF);
    long cashEur = insertAccount("Cash EUR", ASSET, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cardChf, "-100.00", null); // missing frozen base_amount
    insertPosting(txn, cashEur, NINETY_FIVE, NINETY_FIVE);

    assertThat(countRows(SUM_TO_ZERO_VIOLATIONS)).isEqualTo(1L);
  }

  @Test
  void crossCurrencyTransactionUnbalancedInBaseViolatesSumToZero() throws SQLException {
    long cardChf = insertAccount("Card CHF", ASSET, CHF);
    long cashEur = insertAccount("Cash EUR", ASSET, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cardChf, "-100.00", "-95.00");
    insertPosting(txn, cashEur, "97.00", "97.00"); // base sum +2.00, no FX residual leg

    assertThat(countRows(SUM_TO_ZERO_VIOLATIONS)).isEqualTo(1L);
  }

  @Test
  void softDeletedUnbalancedTransactionIsNotViolation() throws SQLException {
    long cash = insertAccount(CASH, ASSET, EUR);
    long food = insertAccount(FOOD_EUR, EXPENSE, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cash, MINUS_5, null);
    insertPosting(txn, food, "4.00", null); // unbalanced...
    ledger.softDeleteTransaction(txn);

    // ...but soft-deleted, so it is out of scope for the invariant (data-model §8 invariant 4).
    assertThat(countRows(SUM_TO_ZERO_VIOLATIONS)).isZero();
  }

  @Test
  void postingToLeafPassesLeavesOnly() throws SQLException {
    long foodParent = insertAccount("Food", EXPENSE, EUR);
    long foodEur = insertChildAccount(FOOD_EUR, EXPENSE, EUR, foodParent);
    long cash = insertAccount(CASH, ASSET, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cash, MINUS_5, null);
    insertPosting(txn, foodEur, "5.00", null); // posts to the leaf, not the parent

    assertThat(countRows(LEAVES_ONLY_VIOLATIONS)).isZero();
  }

  @Test
  void postingToNonLeafParentViolatesLeavesOnly() throws SQLException {
    long foodParent = insertAccount("Food", EXPENSE, EUR);
    insertChildAccount(FOOD_EUR, EXPENSE, EUR, foodParent);
    long cash = insertAccount(CASH, ASSET, EUR);
    long txn = insertTransaction(JUNE_1);
    insertPosting(txn, cash, MINUS_5, null);
    insertPosting(txn, foodParent, "5.00", null); // posts to the parent — forbidden

    assertThat(countRows(LEAVES_ONLY_VIOLATIONS)).isEqualTo(1L);
  }
}
