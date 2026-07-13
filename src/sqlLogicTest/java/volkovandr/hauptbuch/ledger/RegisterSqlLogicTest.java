package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.RegisterRepository;

/**
 * SQL-logic tier (plan §1.5): the register's two queries on {@link RegisterRepository} — {@link
 * RegisterRepository#findRows} (a windowed per-account running balance over multi-table joins with
 * three interacting filters) and {@link RegisterRepository#findTransactionLegs} (the sibling-leg
 * lookup, ordered by magnitude, feeding the Category cell). Both are logic that lives in the SQL,
 * so they are tested here rather than as row-mapping round-trips (CLAUDE.md §6).
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} only
 * seeds crafted books and reads nothing back but through the repository. {@code @Transactional}
 * rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class RegisterSqlLogicTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final String ASSET = "asset";
  private static final String EXPENSE = "expense";
  private static final String CASH = "Cash";
  private static final String GIRO = "Giro";
  private static final String FOOD = "Food";
  private static final String JAN_1 = "2026-01-01";
  private static final String JAN_5 = "2026-01-05";
  private static final String JAN_10 = "2026-01-10";
  private static final String HUNDRED = "100.00";
  private static final String MINUS_100 = "-100.00";
  private static final String TEN = "10.00";
  private static final String THIRTY = "30.00";
  private static final String MINUS_200 = "-200.00";
  private static final String FEB_1 = "2026-02-01";

  @Autowired JdbcClient jdbcClient;
  @Autowired RegisterRepository registerRepository;

  // ── seeding helpers ───────────────────────────────────────────────────────

  private long insertAccount(String name, String type, String currency, Integer hue) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, hue) values (:n, :t, :c, :h) "
                + "returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .param("h", hue)
        .query(Long.class)
        .single();
  }

  /** An auto-managed currency leaf (data-model §6.5) — named after the bare currency code. */
  private long insertCurrencyLeaf(String currency, String type, long parentId) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id, currency_leaf) "
                + "values (:n, :t, :c, :p, true) "
                + "returning account_id")
        .param("n", currency)
        .param("t", type)
        .param("c", currency)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private long insertPayee(String name) {
    return jdbcClient
        .sql("insert into payee (name) values (:n) returning payee_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private long insertTxn(String date, Long payeeId, String lifecycle) {
    return jdbcClient
        .sql(
            "insert into transaction (date, payee_id, lifecycle) values (:d, :p, :l) "
                + "returning transaction_id")
        .param("d", LocalDate.parse(date))
        .param("p", payeeId)
        .param("l", lifecycle)
        .query(Long.class)
        .single();
  }

  private void insertPosting(long txnId, long accountId, String amount) {
    insertPosting(txnId, accountId, amount, null);
  }

  private void insertPosting(long txnId, long accountId, String amount, String baseAmount) {
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, base_amount) "
                + "values (:t, :a, :amt, :base)")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .param("base", baseAmount == null ? null : new BigDecimal(baseAmount))
        .update();
  }

  private void softDeleteTxn(long txnId) {
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :t")
        .param("t", txnId)
        .update();
  }

  /**
   * Record an ordinary single-leg expense-style posting to {@code account} (a category leg pair).
   */
  private long spend(String date, Long payeeId, long fromAccount, long category, String magnitude) {
    long txn = insertTxn(date, payeeId, "confirmed");
    insertPosting(txn, fromAccount, "-" + magnitude);
    insertPosting(txn, category, magnitude);
    return txn;
  }

  // ── findRows: running balance ─────────────────────────────────────────────

  @Test
  void rowsCarryPerAccountRunningBalanceInDateOrder() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    spend(JAN_1, null, cash, food, HUNDRED);
    spend(JAN_5, null, cash, food, THIRTY);
    spend(JAN_10, null, cash, food, "20.00");

    List<RegisterRow> rows = registerRepository.findRows(List.of(cash), null, null, null, EUR);

    assertThat(rows)
        .extracting(RegisterRow::amount, RegisterRow::runningBalance)
        .usingElementComparator(RegisterSqlLogicTest::compareTuples)
        .containsExactly(
            tuple(bd(MINUS_100), bd(MINUS_100)),
            tuple(bd("-30.00"), bd("-130.00")),
            tuple(bd("-20.00"), bd("-150.00")));
  }

  @Test
  void balanceIsThreadedPerAccountAndRowsInterleaveByDate() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long giro = insertAccount(GIRO, ASSET, EUR, 30);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);

    spend(JAN_1, null, cash, food, HUNDRED); // Cash: -100
    spend("2026-01-02", null, giro, food, "40.00"); // Giro: -40
    spend("2026-01-03", null, cash, food, TEN); // Cash: -110
    spend("2026-01-04", null, giro, food, "5.00"); // Giro: -45

    List<RegisterRow> rows =
        registerRepository.findRows(List.of(cash, giro), null, null, null, EUR);

    // Interleaved by date, but each account keeps its own balance thread.
    assertThat(rows)
        .extracting(RegisterRow::accountName, RegisterRow::runningBalance)
        .usingElementComparator(RegisterSqlLogicTest::compareTuples)
        .containsExactly(
            tuple(CASH, bd(MINUS_100)),
            tuple(GIRO, bd("-40.00")),
            tuple(CASH, bd("-110.00")),
            tuple(GIRO, bd("-45.00")));
  }

  @Test
  void runningBalanceAccumulatesFromBeforeTheDateWindow() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    spend(JAN_1, null, cash, food, HUNDRED); // outside the window (below)
    spend(FEB_1, null, cash, food, THIRTY); // inside
    spend("2026-02-10", null, cash, food, "20.00"); // inside

    // Only February rows are shown, but the balance carries the January opening forward.
    List<RegisterRow> rows =
        registerRepository.findRows(List.of(cash), LocalDate.parse(FEB_1), null, null, EUR);

    assertThat(rows)
        .extracting(RegisterRow::date, RegisterRow::runningBalance)
        .usingElementComparator(RegisterSqlLogicTest::compareTuples)
        .containsExactly(
            tuple(LocalDate.parse(FEB_1), bd("-130.00")),
            tuple(LocalDate.parse("2026-02-10"), bd("-150.00")));
  }

  @Test
  void backdatedInsertShiftsTheBalancesOfRowsBelowIt() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    spend(JAN_1, null, cash, food, HUNDRED);
    spend(JAN_10, null, cash, food, "50.00");
    // A backdated row slots in between the two existing rows.
    spend(JAN_5, null, cash, food, "20.00");

    List<RegisterRow> rows = registerRepository.findRows(List.of(cash), null, null, null, EUR);

    assertThat(rows)
        .extracting(RegisterRow::date, RegisterRow::runningBalance)
        .usingElementComparator(RegisterSqlLogicTest::compareTuples)
        .containsExactly(
            tuple(LocalDate.parse(JAN_1), bd(MINUS_100)),
            tuple(LocalDate.parse(JAN_5), bd("-120.00")),
            tuple(LocalDate.parse(JAN_10), bd("-170.00")));
  }

  @Test
  void softDeletedTransactionsAreExcludedFromRowsAndBalance() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    spend(JAN_1, null, cash, food, HUNDRED);
    long voided = spend(JAN_5, null, cash, food, THIRTY);
    spend(JAN_10, null, cash, food, "20.00");
    softDeleteTxn(voided);

    List<RegisterRow> rows = registerRepository.findRows(List.of(cash), null, null, null, EUR);

    // The voided row is gone and never counted in the running balance.
    assertThat(rows)
        .extracting(RegisterRow::runningBalance)
        .usingElementComparator(BigDecimal::compareTo)
        .containsExactly(bd(MINUS_100), bd("-120.00"));
  }

  // ── findRows: two-row transfer, filters, currency ─────────────────────────

  @Test
  void transferBetweenTwoViewedAccountsYieldsOneRowPerLeg() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long giro = insertAccount(GIRO, ASSET, EUR, 30);
    long txn = insertTxn(JAN_1, null, "confirmed");
    insertPosting(txn, giro, MINUS_200);
    insertPosting(txn, cash, "200.00");

    List<RegisterRow> rows =
        registerRepository.findRows(List.of(cash, giro), null, null, null, EUR);

    // Both legs of the same transaction appear, each on its own account thread.
    assertThat(rows)
        .extracting(RegisterRow::accountName, RegisterRow::amount)
        .usingElementComparator(RegisterSqlLogicTest::compareTuples)
        .containsExactlyInAnyOrder(tuple(GIRO, bd(MINUS_200)), tuple(CASH, bd("200.00")));
  }

  @Test
  void filteringToOneAccountShowsOnlyItsLegOfTransfer() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long giro = insertAccount(GIRO, ASSET, EUR, 30);
    long txn = insertTxn(JAN_1, null, "confirmed");
    insertPosting(txn, giro, MINUS_200);
    insertPosting(txn, cash, "200.00");

    List<RegisterRow> rows = registerRepository.findRows(List.of(cash), null, null, null, EUR);

    assertThat(rows).extracting(RegisterRow::accountName).containsExactly(CASH);
  }

  @Test
  void payeeFilterShowsOnlyThatPayeesRows() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    long rewe = insertPayee("Rewe");
    long lidl = insertPayee("Lidl");
    spend(JAN_1, rewe, cash, food, TEN);
    spend("2026-01-02", lidl, cash, food, "20.00");
    spend("2026-01-03", rewe, cash, food, THIRTY);

    List<RegisterRow> rows = registerRepository.findRows(List.of(cash), null, null, rewe, EUR);

    assertThat(rows)
        .extracting(RegisterRow::payeeName, RegisterRow::amount)
        .usingElementComparator(RegisterSqlLogicTest::compareTuples)
        .containsExactly(tuple("Rewe", bd("-10.00")), tuple("Rewe", bd("-30.00")));
  }

  @Test
  void dateRangeUpperBoundIsInclusiveAndHidesLaterRows() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    spend(JAN_1, null, cash, food, TEN);
    spend("2026-01-31", null, cash, food, "20.00");
    spend(FEB_1, null, cash, food, THIRTY);

    List<RegisterRow> rows =
        registerRepository.findRows(
            List.of(cash), LocalDate.parse(JAN_1), LocalDate.parse("2026-01-31"), null, EUR);

    assertThat(rows)
        .extracting(RegisterRow::date)
        .containsExactly(LocalDate.parse(JAN_1), LocalDate.parse("2026-01-31"));
  }

  @Test
  void nonBaseCurrencyRowsAreFlaggedAndCarryTheirOwnThread() {
    long visa = insertAccount("Visa CHF", ASSET, CHF, 140);
    long food = insertAccount("Food CHF", EXPENSE, CHF, null);
    // A CHF-only transaction: sums to zero in native CHF, no base_amount needed.
    long txn = insertTxn(JAN_1, null, "confirmed");
    insertPosting(txn, visa, "-80.00");
    insertPosting(txn, food, "80.00");

    List<RegisterRow> rows = registerRepository.findRows(List.of(visa), null, null, null, EUR);

    assertThat(rows)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.currencyCode()).isEqualTo(CHF);
              assertThat(r.baseCurrency()).isFalse();
              assertThat(r.runningBalance()).isEqualByComparingTo("-80.00");
            });
  }

  @Test
  void emptyAccountSelectionYieldsNoRows() {
    assertThat(registerRepository.findRows(List.of(), null, null, null, EUR)).isEmpty();
  }

  @Test
  void pendingReviewRowsAreReturnedCarryingTheirLifecycle() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    long txn = insertTxn(JAN_1, null, "pending_review");
    insertPosting(txn, cash, "-10.00");
    insertPosting(txn, food, TEN);

    List<RegisterRow> rows = registerRepository.findRows(List.of(cash), null, null, null, EUR);

    assertThat(rows).singleElement().extracting(RegisterRow::lifecycle).isEqualTo("pending_review");
  }

  // ── findTransactionLegs: the Category-cell material ───────────────────────

  @Test
  void transactionLegsReturnEveryLegRankedByMagnitude() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    long fun = insertAccount("Fun", EXPENSE, EUR, null);
    // A split: Cash -31,50 = Food +21,50 + Fun +10,00.
    long txn = insertTxn(JAN_1, null, "confirmed");
    insertPosting(txn, cash, "-31.50");
    insertPosting(txn, food, "21.50");
    insertPosting(txn, fun, TEN);

    List<RegisterCounterpartLeg> legs = registerRepository.findTransactionLegs(List.of(txn));

    // Every leg comes back biggest-magnitude first — the renderer, not the SQL, drops each row's
    // own
    // leg (register §2.6): Cash (31,50), Food (21,50), Fun (10,00).
    assertThat(legs)
        .extracting(RegisterCounterpartLeg::accountName, RegisterCounterpartLeg::accountType)
        .containsExactly(tuple(CASH, ASSET), tuple(FOOD, EXPENSE), tuple("Fun", EXPENSE));
  }

  @Test
  void transactionLegsOfTransferReturnBothOwnAccounts() {
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long giro = insertAccount(GIRO, ASSET, EUR, 30);
    long txn = insertTxn(JAN_1, null, "confirmed");
    insertPosting(txn, giro, MINUS_200);
    insertPosting(txn, cash, "200.00");

    // A transfer's two own-account legs both come back — so each row can show the other one, even
    // when both accounts are viewed (plan stage 7d.3, the empty-Category-cell fix).
    List<RegisterCounterpartLeg> legs = registerRepository.findTransactionLegs(List.of(txn));

    assertThat(legs)
        .extracting(RegisterCounterpartLeg::accountName)
        .containsExactlyInAnyOrder(CASH, GIRO);
  }

  @Test
  void transactionLegsOfNoTransactionsIsEmpty() {
    assertThat(registerRepository.findTransactionLegs(List.of())).isEmpty();
  }

  @Test
  void transactionLegOnAnAutoManagedCurrencyLeafRollsUpToItsSemanticParent() {
    // A cross-currency spend routes onto a hidden per-currency leaf (data-model §6.5, plan stage
    // 7d.1); the Category cell must show the semantic parent, never the leaf's own bare-code name.
    long cash = insertAccount(CASH, ASSET, EUR, 210);
    long food = insertAccount(FOOD, EXPENSE, EUR, null);
    long foodChf = insertCurrencyLeaf(CHF, EXPENSE, food);
    long txn = insertTxn(JAN_1, null, "confirmed");
    insertPosting(txn, cash, "-9.10");
    insertPosting(txn, foodChf, TEN);

    List<RegisterCounterpartLeg> legs = registerRepository.findTransactionLegs(List.of(txn));

    // The foodChf leg rolls up to Food; the Cash leg is returned as-is (own accounts are never
    // currency leaves).
    assertThat(legs)
        .filteredOn(leg -> !CASH.equals(leg.accountName()))
        .singleElement()
        .satisfies(
            leg -> {
              assertThat(leg.accountId()).isEqualTo(food);
              assertThat(leg.accountName()).isEqualTo(FOOD);
              assertThat(leg.accountType()).isEqualTo(EXPENSE);
            });
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }

  /**
   * Compare AssertJ tuples element-wise, using {@link BigDecimal#compareTo} for {@link BigDecimal}
   * members so scale differences ({@code numeric(19,4)} returns scale-4) do not fail equality.
   */
  private static int compareTuples(Object left, Object right) {
    List<Object> lhs = ((org.assertj.core.groups.Tuple) left).toList();
    List<Object> rhs = ((org.assertj.core.groups.Tuple) right).toList();
    for (int i = 0; i < lhs.size(); i++) {
      Object a = lhs.get(i);
      Object b = rhs.get(i);
      int cmp =
          (a instanceof BigDecimal ba && b instanceof BigDecimal bb)
              ? ba.compareTo(bb)
              : java.util.Objects.equals(a, b) ? 0 : 1;
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }
}
