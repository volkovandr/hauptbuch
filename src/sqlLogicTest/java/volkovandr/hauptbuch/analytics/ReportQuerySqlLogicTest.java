package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.analytics.repository.QueryConstraints;
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ReportQueryRepository} — the report engine's grouped reads.
 * Covers every measure×currency variant, the leg selections, cross-currency valuation under the
 * turnover rule (data-model §6.1), the tag double-tag dedup (§10.3), the scope defaults
 * (reporting.md §6.4), every dimension in the catalogue (§4), the scope subtree restriction (§6.1)
 * and every filter field/level/operator combination (§6.2–§6.3). Boots Spring so the query under
 * test is the real repository SQL.
 *
 * <p>{@code posting.amount} is {@code numeric(19,4)}, so a summed result carries a different scale
 * than a hand-typed {@code "50.00"} literal — every amount assertion compares numerically ({@link
 * #amount}/{@code isEqualByComparingTo}), never by {@link BigDecimal#equals}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReportQuerySqlLogicTest {

  private static final String EUR = "EUR";
  private static final String CHF = "CHF";
  private static final List<String> INCOME_EXPENSE = List.of("income", "expense");
  private static final List<String> ASSET_LIABILITY = List.of("asset", "liability");
  private static final QueryConstraints NONE = QueryConstraints.NONE;

  @Autowired JdbcClient jdbcClient;
  @Autowired ReportQueryRepository repository;

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

  private long insertTag(String name, Long parentId) {
    return jdbcClient
        .sql("insert into tag (name, parent_id) values (:n, :p) returning tag_id")
        .param("n", name)
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

  private long insertPerson(String name) {
    return jdbcClient
        .sql("insert into person (name) values (:n) returning person_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  /**
   * A person's debt leaf: an asset account flagged {@code person_leaf} and linked via {@code
   * account_owner}.
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

  private long insertTransaction(LocalDate date, boolean pendingReview, boolean deleted) {
    return insertTransaction(date, pendingReview, deleted, null, null);
  }

  private long insertTransaction(
      LocalDate date, boolean pendingReview, boolean deleted, Long payeeId, String note) {
    return jdbcClient
        .sql(
            """
            insert into transaction (date, lifecycle, deleted_at, payee_id, note)
            values (:d, :l, case when :deleted then now() end, :payeeId, :note)
            returning transaction_id
            """)
        .param("d", date)
        .param("l", pendingReview ? "pending_review" : "confirmed")
        .param("deleted", deleted)
        .param("payeeId", payeeId)
        .param("note", note)
        .query(Long.class)
        .single();
  }

  private long insertPosting(long txnId, long accountId, String amount, String baseAmount) {
    return jdbcClient
        .sql(
            """
            insert into posting (transaction_id, account_id, amount, base_amount)
            values (:t, :a, :amt, :base)
            returning posting_id
            """)
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .param("base", baseAmount == null ? null : new BigDecimal(baseAmount))
        .query(Long.class)
        .single();
  }

  private void setPostingNote(long postingId, String note) {
    jdbcClient
        .sql("update posting set note = :note where posting_id = :p")
        .param("note", note)
        .param("p", postingId)
        .update();
  }

  private void setPostingReconciliation(long postingId, String state) {
    jdbcClient
        .sql("update posting set reconciliation = :r where posting_id = :p")
        .param("r", state)
        .param("p", postingId)
        .update();
  }

  private void tag(long postingId, long tagId) {
    jdbcClient
        .sql("insert into posting_tag (posting_id, tag_id) values (:p, :t)")
        .param("p", postingId)
        .param("t", tagId)
        .update();
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

  private long postSingleCurrency(
      long payerAccountId, long categoryAccountId, LocalDate date, String amount) {
    long txn = insertTransaction(date, false, false);
    insertPosting(txn, payerAccountId, "-" + amount, null);
    insertPosting(txn, categoryAccountId, amount, null);
    return txn;
  }

  private static RawTurnoverCell byLabelAndMonth(
      List<RawTurnoverCell> cells, String label, String month) {
    return cells.stream()
        .filter(c -> c.dimensionLabel().equals(label) && c.monthKey().equals(month))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("No cell for " + label + "/" + month + " in " + cells));
  }

  private static RawBalanceCell byLabel(List<RawBalanceCell> cells, String label) {
    return cells.stream()
        .filter(c -> c.dimensionLabel().equals(label))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No cell for " + label + " in " + cells));
  }

  private static void amount(BigDecimal actual, String expected) {
    assertThat(actual).isEqualByComparingTo(new BigDecimal(expected));
  }

  // ── accountTreeTurnover ───────────────────────────────────────────────────

  @Test
  void groupsTurnoverByTopLevelCategoryAncestorAndMonth() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long restaurants = insertAccount("Restaurants", "expense", EUR, food);

    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 15), "20.00");
    postSingleCurrency(cash, restaurants, LocalDate.of(2026, 1, 20), "30.00");
    postSingleCurrency(cash, food, LocalDate.of(2026, 2, 1), "5.00");

    List<RawTurnoverCell> cells =
        repository.accountTreeTurnover(
            INCOME_EXPENSE,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 2, 28),
            EUR,
            "NET",
            true,
            false,
            NONE);

    // Restaurants (a child of Food) rolls up under Food's top-level id — Food and its child are
    // one row (stage a renders fully collapsed; expansion is stage e).
    assertThat(cells).hasSize(2);
    amount(byLabelAndMonth(cells, "Food", "2026-01").nativeAmount(), "50.00");
    amount(byLabelAndMonth(cells, "Food", "2026-02").nativeAmount(), "5.00");
  }

  @Test
  void groupsPersonDebtLeavesIntoOnePersonalDebtsBucketPerCurrencyNotTheCosmeticLeafName() {
    long cashEur = insertAccount("Cash", "asset", EUR, null);
    long cashChf = insertAccount("Cash CHF", "asset", CHF, null);
    long alice = insertPerson("Alice");
    long bob = insertPerson("Bob");
    long carol = insertPerson("Carol");
    long aliceEur = insertPersonAccount(alice, "personal.EUR", EUR);
    long bobEur = insertPersonAccount(bob, "personal.EUR", EUR);
    long carolChf = insertPersonAccount(carol, "personal.CHF", CHF);
    postSingleCurrency(cashEur, aliceEur, LocalDate.of(2026, 1, 5), "30.00");
    postSingleCurrency(cashEur, bobEur, LocalDate.of(2026, 1, 6), "20.00");
    postSingleCurrency(cashChf, carolChf, LocalDate.of(2026, 1, 7), "15.00");

    List<RawTurnoverCell> cells =
        repository.accountTreeTurnover(
            ASSET_LIABILITY,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells)
        .extracting(RawTurnoverCell::dimensionLabel)
        .doesNotContain("personal.EUR", "personal.CHF", "Alice", "Bob", "Carol")
        .contains("Personal debts (EUR)", "Personal debts (CHF)");
    amount(byLabelAndMonth(cells, "Personal debts (EUR)", "2026-01").nativeAmount(), "50.00");
    amount(byLabelAndMonth(cells, "Personal debts (CHF)", "2026-01").nativeAmount(), "15.00");
  }

  @Test
  void legSelectionSplitsDebitsAndCreditsIndependentlyOfAccountType() {
    long creditCard = insertAccount("Credit Card", "liability", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);

    // €30 spent on the credit card: Food +30 (debit), CreditCard -30 (credit).
    long txn = insertTransaction(LocalDate.of(2026, 3, 1), false, false);
    insertPosting(txn, food, "30.00", null);
    insertPosting(txn, creditCard, "-30.00", null);
    // Paying the card off €10 from cash: CreditCard +10 (debit), Cash -10 (credit).
    long cash = insertAccount("Cash", "asset", EUR, null);
    long payoff = insertTransaction(LocalDate.of(2026, 3, 2), false, false);
    insertPosting(payoff, creditCard, "10.00", null);
    insertPosting(payoff, cash, "-10.00", null);

    List<RawTurnoverCell> credits =
        repository.accountTreeTurnover(
            List.of("liability"),
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            EUR,
            "CREDITS",
            true,
            false,
            NONE);
    assertThat(credits).hasSize(1);
    amount(credits.get(0).nativeAmount(), "-30.00");

    List<RawTurnoverCell> debits =
        repository.accountTreeTurnover(
            List.of("liability"),
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
            EUR,
            "DEBITS",
            true,
            false,
            NONE);
    assertThat(debits).hasSize(1);
    amount(debits.get(0).nativeAmount(), "10.00");
  }

  @Test
  void baseValuationUsesTheFrozenBaseAmountWhenPresent() {
    long chfCash = insertAccount("Swiss Cash", "asset", CHF, null);
    long food = insertAccount("Food", "expense", CHF, null);
    long txn = insertTransaction(LocalDate.of(2026, 4, 1), false, false);
    // A cross-currency conversion event elsewhere freezes this leg's base_amount; the turnover
    // query must use it verbatim, not recompute from the feed (data-model §6.4).
    insertPosting(txn, food, "100.00", "91.00");
    insertPosting(txn, chfCash, "-100.00", "-91.00");
    insertRate(CHF, LocalDate.of(2026, 4, 1), "0.80"); // must NOT be used — base_amount is frozen

    List<RawTurnoverCell> cells =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 4, 30),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    amount(cells.get(0).baseAmount(), "91.00");
    assertThat(cells.get(0).missingRateCount()).isZero();
  }

  @Test
  void baseValuationFallsBackToTheRateAsOfThePostingsOwnDateWhenNoBaseAmountIsFrozen() {
    long chfCash = insertAccount("Swiss Cash", "asset", CHF, null);
    long food = insertAccount("Food", "expense", CHF, null);
    // Single-currency transaction (CHF cash -> CHF food): base_amount stays NULL (data-model §6.4).
    postSingleCurrency(chfCash, food, LocalDate.of(2026, 1, 10), "10.00");
    insertRate(CHF, LocalDate.of(2025, 12, 1), "0.90"); // carries forward to Jan 10th

    List<RawTurnoverCell> cells =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    amount(cells.get(0).baseAmount(), "9.00");
  }

  @Test
  void missingRateMakesTheBaseValuationUnreliableAndFlagsIt() {
    long chfCash = insertAccount("Swiss Cash", "asset", CHF, null);
    long food = insertAccount("Food", "expense", CHF, null);
    postSingleCurrency(chfCash, food, LocalDate.of(2026, 1, 10), "10.00");
    // No exchange_rate row at all for CHF.

    List<RawTurnoverCell> cells =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).missingRateCount()).isEqualTo(1L);
  }

  @Test
  void excludesVoidedTransactionsClosedAccountsAndPendingReviewByDefault() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 5), "10.00");

    long voidedTxn = insertTransaction(LocalDate.of(2026, 1, 6), false, true);
    insertPosting(voidedTxn, cash, "-999.00", null);
    insertPosting(voidedTxn, food, "999.00", null);

    long pendingTxn = insertTransaction(LocalDate.of(2026, 1, 7), true, false);
    insertPosting(pendingTxn, cash, "-500.00", null);
    insertPosting(pendingTxn, food, "500.00", null);

    List<RawTurnoverCell> excludingPending =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);
    assertThat(excludingPending).hasSize(1);
    amount(excludingPending.get(0).nativeAmount(), "10.00");

    List<RawTurnoverCell> includingPending =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            true,
            NONE);
    assertThat(includingPending).hasSize(1);
    amount(includingPending.get(0).nativeAmount(), "510.00");
  }

  @Test
  void excludesClosedAccountsOnlyWhenToldTo() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 5), "10.00");
    jdbcClient
        .sql("update account set closed_at = date '2026-01-06' where account_id = :a")
        .param("a", food)
        .update();

    List<RawTurnoverCell> excludingClosed =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            false,
            false,
            NONE);
    assertThat(excludingClosed).isEmpty();

    List<RawTurnoverCell> includingClosed =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);
    assertThat(includingClosed).hasSize(1);
  }

  // ── tagTurnover ───────────────────────────────────────────────────────────

  @Test
  void postingDoubleTaggedInTheSameFamilyCountsOnceUnderTheTopLevelTag() {
    long car = insertTag("Car", null);
    long audi = insertTag("Audi", car);
    long skoda = insertTag("Skoda", car);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long fuel = insertAccount("Fuel", "expense", EUR, null);

    long txn = insertTransaction(LocalDate.of(2026, 5, 1), false, false);
    insertPosting(txn, cash, "-40.00", null);
    long fuelLeg = insertPosting(txn, fuel, "40.00", null);
    tag(fuelLeg, audi);
    tag(fuelLeg, skoda);

    List<RawTurnoverCell> cells =
        repository.tagTurnover(
            List.of("expense"),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).dimensionLabel()).isEqualTo("Car");
    amount(cells.get(0).nativeAmount(), "40.00");
  }

  @Test
  void postingsTaggedInDifferentTopLevelFamiliesEachCountUnderTheirOwnLens() {
    long car = insertTag("Car", null);
    long audi = insertTag("Audi", car);
    long trip = insertTag("Trip", null);
    long prague = insertTag("Prague", trip);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long fuel = insertAccount("Fuel", "expense", EUR, null);

    long txn = insertTransaction(LocalDate.of(2026, 5, 1), false, false);
    insertPosting(txn, cash, "-40.00", null);
    long fuelLeg = insertPosting(txn, fuel, "40.00", null);
    tag(fuelLeg, audi);
    tag(fuelLeg, prague);

    List<RawTurnoverCell> cells =
        repository.tagTurnover(
            List.of("expense"),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    // The same posting legitimately appears once per top-level lens (data-model §10.3).
    assertThat(cells)
        .extracting(RawTurnoverCell::dimensionLabel)
        .containsExactlyInAnyOrder("Car", "Trip");
    amount(byLabelAndMonth(cells, "Car", "2026-05").nativeAmount(), "40.00");
    amount(byLabelAndMonth(cells, "Trip", "2026-05").nativeAmount(), "40.00");
  }

  // ── totalTurnover ─────────────────────────────────────────────────────────

  @Test
  void totalTurnoverCollapsesEveryInScopePostingIntoOneRowPerMonthAndCurrency() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long fuel = insertAccount("Fuel", "expense", EUR, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 6, 1), "20.00");
    postSingleCurrency(cash, fuel, LocalDate.of(2026, 6, 2), "15.00");

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "35.00");
  }

  // ── accountTreeClosingBalance ─────────────────────────────────────────────

  @Test
  void closingBalanceIsTheCumulativeNativeSumUpToAndIncludingAsOf() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "1000.00");
    long spend = insertAccount("Food", "expense", EUR, null);
    postSingleCurrency(cash, spend, LocalDate.of(2026, 1, 10), "50.00");
    postSingleCurrency(
        cash, spend, LocalDate.of(2026, 2, 1), "20.00"); // after asOf, must not count

    List<RawBalanceCell> cells =
        repository.accountTreeClosingBalance(
            ASSET_LIABILITY, LocalDate.of(2026, 1, 31), true, false, NONE);

    assertThat(cells).hasSize(1);
    amount(byLabel(cells, "Cash").nativeBalance(), "950.00");
  }

  @Test
  void closingBalanceExcludesClosedAccountsOnlyWhenToldTo() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    jdbcClient
        .sql("update account set closed_at = date '2026-01-01' where account_id = :a")
        .param("a", cash)
        .update();

    assertThat(
            repository.accountTreeClosingBalance(
                List.of("asset"), LocalDate.of(2026, 6, 1), false, false, NONE))
        .isEmpty();
    assertThat(
            repository.accountTreeClosingBalance(
                List.of("asset"), LocalDate.of(2026, 6, 1), true, false, NONE))
        .hasSize(1);
  }

  @Test
  void closingBalanceExcludesPendingReviewTransactionsOnlyByDefault() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    long pendingTxn = insertTransaction(LocalDate.of(2025, 6, 1), true, false);
    insertPosting(pendingTxn, cash, "500.00", null);
    insertPosting(pendingTxn, opening, "-500.00", null);

    List<RawBalanceCell> excludingPending =
        repository.accountTreeClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 1), true, false, NONE);
    assertThat(excludingPending).hasSize(1);
    amount(byLabel(excludingPending, "Cash").nativeBalance(), "100.00");

    List<RawBalanceCell> includingPending =
        repository.accountTreeClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 1), true, true, NONE);
    assertThat(includingPending).hasSize(1);
    amount(byLabel(includingPending, "Cash").nativeBalance(), "600.00");
  }

  @Test
  void closingBalanceGroupsEveryPersonsDebtLeafIntoOnePersonalDebtsBucketPerCurrency() {
    long cashEur = insertAccount("Cash", "asset", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cashEur, LocalDate.of(2025, 1, 1), "1000.00");
    long alice = insertPerson("Alice");
    long bob = insertPerson("Bob");
    long carol = insertPerson("Carol");
    long aliceEur = insertPersonAccount(alice, "personal.EUR", EUR);
    long bobEur = insertPersonAccount(bob, "personal.EUR", EUR);
    long carolChf = insertPersonAccount(carol, "personal.CHF", CHF);
    postSingleCurrency(cashEur, aliceEur, LocalDate.of(2026, 1, 5), "30.00");
    postSingleCurrency(cashEur, bobEur, LocalDate.of(2026, 1, 6), "20.00");
    long cashChf = insertAccount("Cash CHF", "asset", CHF, null);
    postSingleCurrency(cashChf, carolChf, LocalDate.of(2026, 1, 7), "15.00");

    List<RawBalanceCell> cells =
        repository.accountTreeClosingBalance(
            ASSET_LIABILITY, LocalDate.of(2026, 1, 31), true, false, NONE);

    assertThat(cells)
        .extracting(RawBalanceCell::dimensionLabel)
        .doesNotContain("personal.EUR", "personal.CHF", "Alice", "Bob", "Carol")
        .contains("Personal debts (EUR)", "Personal debts (CHF)");
    amount(byLabel(cells, "Personal debts (EUR)").nativeBalance(), "50.00");
    amount(byLabel(cells, "Personal debts (CHF)").nativeBalance(), "15.00");
  }

  @Test
  void totalClosingBalanceCollapsesEveryInScopeAccountIntoOneRowPerCurrency() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long card = insertAccount("Credit Card", "liability", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    postSingleCurrency(card, opening, LocalDate.of(2025, 1, 1), "20.00");

    List<RawBalanceCell> cells =
        repository.totalClosingBalance(
            ASSET_LIABILITY, LocalDate.of(2026, 1, 1), true, false, NONE);

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeBalance(), "80.00");
  }

  @Test
  void totalClosingBalanceExcludesClosedAccountsOnlyWhenToldTo() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    jdbcClient
        .sql("update account set closed_at = date '2026-01-01' where account_id = :a")
        .param("a", cash)
        .update();

    assertThat(
            repository.totalClosingBalance(
                List.of("asset"), LocalDate.of(2026, 6, 1), false, false, NONE))
        .isEmpty();
    assertThat(
            repository.totalClosingBalance(
                List.of("asset"), LocalDate.of(2026, 6, 1), true, false, NONE))
        .hasSize(1);
  }

  // ── candidate lookups ─────────────────────────────────────────────────────

  @Test
  void topLevelAccountsListsOnlyTopLevelNonCurrencyLeafLiveAccountsOfTheGivenTypes() {
    long food = insertAccount("Food", "expense", EUR, null);
    insertAccount("Restaurants", "expense", EUR, food); // not top-level — excluded
    insertAccount("Salary", "income", EUR, null); // wrong type — excluded
    long deleted = insertAccount("Gone", "expense", EUR, null);
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :a")
        .param("a", deleted)
        .update();

    assertThat(repository.topLevelAccounts(List.of("expense"), true))
        .extracting(TopLevelNode::label)
        .containsExactly("Food");
  }

  @Test
  void topLevelAccountsExcludesCurrencyLeavesAndClosedAccountsByDefault() {
    long food = insertAccount("Food", "expense", EUR, null);
    jdbcClient
        .sql("update account set currency_leaf = true where account_id = :a")
        .param("a", food)
        .update();
    long fuel = insertAccount("Fuel", "expense", EUR, null);
    jdbcClient
        .sql("update account set closed_at = date '2026-01-01' where account_id = :a")
        .param("a", fuel)
        .update();

    assertThat(repository.topLevelAccounts(List.of("expense"), false)).isEmpty();
    assertThat(repository.topLevelAccounts(List.of("expense"), true))
        .extracting(TopLevelNode::label)
        .containsExactly("Fuel");
  }

  @Test
  void topLevelAccountsGroupsPersonDebtLeavesIntoOnePersonalDebtsCandidatePerCurrency() {
    insertAccount("Cash", "asset", EUR, null);
    long alice = insertPerson("Alice");
    long bob = insertPerson("Bob");
    long carol = insertPerson("Carol");
    insertPersonAccount(alice, "personal.EUR", EUR);
    insertPersonAccount(bob, "personal.EUR", EUR);
    insertPersonAccount(carol, "personal.CHF", CHF);

    assertThat(repository.topLevelAccounts(ASSET_LIABILITY, true))
        .extracting(TopLevelNode::label)
        .doesNotContain("personal.EUR", "personal.CHF", "Alice", "Bob", "Carol")
        .containsExactlyInAnyOrder("Cash", "Personal debts (EUR)", "Personal debts (CHF)");
  }

  @Test
  void topLevelTagsListsOnlyLiveTopLevelTags() {
    long car = insertTag("Car", null);
    insertTag("Audi", car); // not top-level — excluded
    long trip = insertTag("Trip", null);
    jdbcClient.sql("update tag set deleted_at = now() where tag_id = :t").param("t", trip).update();

    assertThat(repository.topLevelTags()).extracting(TopLevelNode::label).containsExactly("Car");
  }

  // ── payeeTurnover / payeeCandidates ───────────────────────────────────────

  @Test
  void payeeTurnoverGroupsByTheTransactionsPayee() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long shopAaa = insertPayee("ShopAaa");
    long txn = insertTransaction(LocalDate.of(2026, 7, 1), false, false, shopAaa, null);
    insertPosting(txn, cash, "-25.00", null);
    insertPosting(txn, food, "25.00", null);

    List<RawTurnoverCell> cells =
        repository.payeeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).dimensionLabel()).isEqualTo("ShopAaa");
    amount(cells.get(0).nativeAmount(), "25.00");
  }

  @Test
  void payeeTurnoverGroupsNoPayeePostingsUnderTheSyntheticNoPayeeRowRatherThanDroppingThem() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 7, 1), "10.00"); // no payee

    List<RawTurnoverCell> cells =
        repository.payeeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).dimensionLabel()).isEqualTo("(No payee)");
    amount(cells.get(0).nativeAmount(), "10.00");
  }

  @Test
  void payeeCandidatesListsTheNoPayeePlaceholderAndOnlyLivePayees() {
    insertPayee("ShopAaa");
    long deleted = insertPayee("Gone");
    jdbcClient
        .sql("update payee set deleted_at = now() where payee_id = :p")
        .param("p", deleted)
        .update();

    assertThat(repository.payeeCandidates())
        .extracting(TopLevelNode::label)
        .containsExactly("(No payee)", "ShopAaa");
  }

  // ── personTurnover / personClosingBalance / personCandidates ─────────────

  @Test
  void personTurnoverGroupsByTheDebtLeafsOwnerAndIgnoresNonPersonAccounts() {
    long max = insertPerson("Max");
    long maxDebt = insertPersonAccount(max, "Max-EUR", EUR);
    long cash = insertAccount("Cash", "asset", EUR, null);
    // Lending Max €30: Max-EUR +30 (debit, he owes more), Cash -30.
    postSingleCurrency(cash, maxDebt, LocalDate.of(2026, 8, 1), "30.00");
    // An ordinary asset account must not be swept in even though its type matches.
    long savings = insertAccount("Savings", "asset", EUR, null);
    postSingleCurrency(cash, savings, LocalDate.of(2026, 8, 2), "999.00");

    List<RawTurnoverCell> cells =
        repository.personTurnover(
            List.of("asset"),
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells).hasSize(1);
    assertThat(cells.get(0).dimensionLabel()).isEqualTo("Max");
    amount(cells.get(0).nativeAmount(), "30.00");
  }

  @Test
  void personClosingBalanceIsTheCumulativeNativeSumOfTheirDebtLeaves() {
    long max = insertPerson("Max");
    long maxDebt = insertPersonAccount(max, "Max-EUR", EUR);
    long cash = insertAccount("Cash", "asset", EUR, null);
    postSingleCurrency(cash, maxDebt, LocalDate.of(2026, 1, 5), "30.00");
    postSingleCurrency(maxDebt, cash, LocalDate.of(2026, 1, 20), "10.00"); // Max repays €10

    List<RawBalanceCell> cells =
        repository.personClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 31), true, false, NONE);

    assertThat(cells).hasSize(1);
    amount(byLabel(cells, "Max").nativeBalance(), "20.00");
  }

  @Test
  void personCandidatesListsOnlyLivePersonsWithProvisionedLeaf() {
    long max = insertPerson("Max");
    insertPersonAccount(max, "Max-EUR", EUR);
    insertPerson("Nobody"); // no debt leaf provisioned — excluded
    long gone = insertPerson("Gone");
    insertPersonAccount(gone, "Gone-EUR", EUR);
    jdbcClient
        .sql("update person set deleted_at = now() where person_id = :p")
        .param("p", gone)
        .update();

    assertThat(repository.personCandidates())
        .extracting(TopLevelNode::label)
        .containsExactly("Max");
  }

  // ── currencyTurnover / currencyClosingBalance / currencyCandidates ───────

  @Test
  void currencyTurnoverGroupsByTheAccountsCurrency() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long chfCash = insertAccount("Swiss Cash", "asset", CHF, null);
    long chfFood = insertAccount("Swiss Food", "expense", CHF, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 9, 1), "20.00");
    postSingleCurrency(chfCash, chfFood, LocalDate.of(2026, 9, 2), "15.00");

    List<RawTurnoverCell> cells =
        repository.currencyTurnover(
            List.of("expense"),
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30),
            EUR,
            "NET",
            true,
            false,
            NONE);

    assertThat(cells)
        .extracting(RawTurnoverCell::dimensionLabel)
        .containsExactlyInAnyOrder(EUR, CHF);
    amount(byLabelAndMonth(cells, EUR, "2026-09").nativeAmount(), "20.00");
    amount(byLabelAndMonth(cells, CHF, "2026-09").nativeAmount(), "15.00");
  }

  @Test
  void currencyClosingBalanceGroupsByTheAccountsCurrency() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    long chfCash = insertAccount("Swiss Cash", "asset", CHF, null);
    long chfOpening = insertAccount("Swiss Opening", "equity", CHF, null);
    postSingleCurrency(chfOpening, chfCash, LocalDate.of(2025, 1, 1), "50.00");

    List<RawBalanceCell> cells =
        repository.currencyClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 1), true, false, NONE);

    assertThat(cells)
        .extracting(RawBalanceCell::dimensionLabel)
        .containsExactlyInAnyOrder(EUR, CHF);
    amount(byLabel(cells, EUR).nativeBalance(), "100.00");
    amount(byLabel(cells, CHF).nativeBalance(), "50.00");
  }

  @Test
  void currencyCandidatesListsEveryDefinedCurrency() {
    assertThat(repository.currencyCandidates())
        .extracting(TopLevelNode::key)
        .contains(EUR, CHF); // the seed migration defines a fixed currency list
  }

  // ── accountTypeTurnover / accountTypeClosingBalance ──────────────────────

  @Test
  void accountTypeTurnoverGroupsByTheAccountsType() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long salary = insertAccount("Salary", "income", EUR, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 10, 1), "20.00");
    postSingleCurrency(salary, cash, LocalDate.of(2026, 10, 2), "2000.00");

    List<RawTurnoverCell> cells =
        repository.accountTypeTurnover(
            List.of("income", "expense"),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            EUR,
            "NET",
            true,
            false,
            NONE);

    // dimension_label is the raw account.type value — no display formatting happens below
    // ReportGridBuilder, which is the one place a label is actually shown (see accountType
    // Candidates' own capitalized labels in ReportDataFetcher).
    assertThat(cells)
        .extracting(RawTurnoverCell::dimensionLabel)
        .containsExactlyInAnyOrder("expense", "income");
    amount(byLabelAndMonth(cells, "expense", "2026-10").nativeAmount(), "20.00");
  }

  @Test
  void accountTypeClosingBalanceGroupsByTheAccountsType() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long card = insertAccount("Credit Card", "liability", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    postSingleCurrency(card, opening, LocalDate.of(2025, 1, 1), "20.00");

    List<RawBalanceCell> cells =
        repository.accountTypeClosingBalance(
            ASSET_LIABILITY, LocalDate.of(2026, 1, 1), true, false, NONE);

    assertThat(cells)
        .extracting(RawBalanceCell::dimensionLabel)
        .containsExactlyInAnyOrder("asset", "liability");
    amount(byLabel(cells, "asset").nativeBalance(), "100.00");
    // Raw, unflipped native sum (the credit-natural display flip is ReportGridBuilder's job, not
    // the repository's) — the card was credited €20 (data-model §4), so it is stored as -20.00.
    amount(byLabel(cells, "liability").nativeBalance(), "-20.00");
  }

  // ── filters (§6.2–§6.3) ────────────────────────────────────────────────────

  @Test
  void postingLevelCategoryFilterRestrictsTheMeasuredPostingsToTheSubtree() {
    long food = insertAccount("Food", "expense", EUR, null);
    long fuel = insertAccount("Fuel", "expense", EUR, null);
    long cash = insertAccount("Cash", "asset", EUR, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    postSingleCurrency(cash, fuel, LocalDate.of(2026, 1, 6), "15.00");
    ReportFilter filter =
        new ReportFilter(
            FilterField.CATEGORY,
            FilterLevel.POSTING,
            FilterOperator.IS_ONE_OF,
            List.of(String.valueOf(food)));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void transactionLevelAccountFilterSumsTheScopedLegOfAnyMatchingTransaction() {
    // reporting.md §6.2's worked example: "what did I spend from BankAaa, by category" — the
    // filter names the BankAaa leg, the measure sums the Food leg of the SAME transaction.
    long bankAaa = insertAccount("BankAaa", "asset", EUR, null);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    postSingleCurrency(bankAaa, food, LocalDate.of(2026, 1, 5), "20.00"); // touches BankAaa
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 6), "15.00"); // does not touch BankAaa
    ReportFilter filter =
        new ReportFilter(
            FilterField.ACCOUNT,
            FilterLevel.TRANSACTION,
            FilterOperator.IS_ONE_OF,
            List.of(String.valueOf(bankAaa)));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void tagFilterExpandsIsOneOfToSubtreeMembership() {
    long trip = insertTag("Trip", null);
    long prague = insertTag("Prague", trip);
    long cash = insertAccount("Cash", "asset", EUR, null);
    long fuel = insertAccount("Fuel", "expense", EUR, null);
    long taggedTxn = insertTransaction(LocalDate.of(2026, 1, 5), false, false);
    insertPosting(taggedTxn, cash, "-20.00", null);
    long taggedLeg = insertPosting(taggedTxn, fuel, "20.00", null);
    tag(taggedLeg, prague);
    postSingleCurrency(cash, fuel, LocalDate.of(2026, 1, 6), "15.00"); // untagged
    ReportFilter filter =
        new ReportFilter(
            FilterField.TAG,
            FilterLevel.POSTING,
            FilterOperator.IS_ONE_OF,
            List.of(String.valueOf(trip)));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void payeeFilterIsOneOfMatchesById() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long shopAaa = insertPayee("ShopAaa");
    long shopBbb = insertPayee("ShopBbb");
    long txnA = insertTransaction(LocalDate.of(2026, 1, 5), false, false, shopAaa, null);
    insertPosting(txnA, cash, "-20.00", null);
    insertPosting(txnA, food, "20.00", null);
    long txnB = insertTransaction(LocalDate.of(2026, 1, 6), false, false, shopBbb, null);
    insertPosting(txnB, cash, "-15.00", null);
    insertPosting(txnB, food, "15.00", null);
    ReportFilter filter =
        new ReportFilter(
            FilterField.PAYEE,
            FilterLevel.TRANSACTION,
            FilterOperator.IS_ONE_OF,
            List.of(String.valueOf(shopAaa)));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void payeeFilterMatchesIsCaseInsensitiveRegex() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long shopAaa = insertPayee("ShopAaa");
    long txn = insertTransaction(LocalDate.of(2026, 1, 5), false, false, shopAaa, null);
    insertPosting(txn, cash, "-20.00", null);
    insertPosting(txn, food, "20.00", null);
    ReportFilter filter =
        new ReportFilter(
            FilterField.PAYEE,
            FilterLevel.TRANSACTION,
            FilterOperator.MATCHES,
            List.of("^shopaaa$"));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void personFilterRestrictsToTheGivenPersonsDebtLeaves() {
    long max = insertPerson("Max");
    long maxDebt = insertPersonAccount(max, "Max-EUR", EUR);
    long other = insertPerson("Other");
    long otherDebt = insertPersonAccount(other, "Other-EUR", EUR);
    long cash = insertAccount("Cash", "asset", EUR, null);
    postSingleCurrency(cash, maxDebt, LocalDate.of(2026, 1, 5), "30.00");
    postSingleCurrency(cash, otherDebt, LocalDate.of(2026, 1, 6), "50.00");
    ReportFilter filter =
        new ReportFilter(
            FilterField.PERSON,
            FilterLevel.POSTING,
            FilterOperator.IS_ONE_OF,
            List.of(String.valueOf(max)));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("asset"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "DEBITS",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "30.00");
  }

  @Test
  void currencyFilterRestrictsToTheGivenCurrencies() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long chfCash = insertAccount("Swiss Cash", "asset", CHF, null);
    long chfFood = insertAccount("Swiss Food", "expense", CHF, null);
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 5), "20.00");
    postSingleCurrency(chfCash, chfFood, LocalDate.of(2026, 1, 6), "15.00");
    ReportFilter filter =
        new ReportFilter(
            FilterField.CURRENCY, FilterLevel.POSTING, FilterOperator.IS_ONE_OF, List.of(CHF));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "15.00");
  }

  @Test
  void accountTypeFilterIsTransactionLevelOnly() {
    // §6.2's exception: ACCOUNT_TYPE has no posting-level reading (that is exactly what scope's
    // own account types already say), so the filter always means "transactions touching a
    // liability" — every in-scope leg of such a transaction is summed, not only the liability leg
    // itself.
    long cash = insertAccount("Cash", "asset", EUR, null);
    long card = insertAccount("Credit Card", "liability", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2026, 1, 1), "100.00");
    postSingleCurrency(card, opening, LocalDate.of(2026, 1, 1), "20.00");
    ReportFilter filter =
        new ReportFilter(
            FilterField.ACCOUNT_TYPE,
            FilterLevel.TRANSACTION,
            FilterOperator.IS_ONE_OF,
            List.of("liability"));

    List<RawBalanceCell> cells =
        repository.totalClosingBalance(
            ASSET_LIABILITY,
            LocalDate.of(2026, 1, 31),
            true,
            false,
            new QueryConstraints(List.of(filter)));

    // Only the card-opening transaction touches a liability; its own in-scope (asset/liability)
    // leg is the card's, the equity leg is out of scope either way.
    assertThat(cells).hasSize(1);
    // Raw, unflipped native sum (see the equivalent note in accountTypeClosingBalance's test).
    amount(cells.get(0).nativeBalance(), "-20.00");
  }

  @Test
  void reconciliationFilterPostingLevelRestrictsToTheMeasuredPostingsOwnState() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long txn = insertTransaction(LocalDate.of(2026, 1, 5), false, false);
    insertPosting(txn, cash, "-20.00", null);
    long foodLeg = insertPosting(txn, food, "20.00", null);
    setPostingReconciliation(foodLeg, "reconciled");
    postSingleCurrency(cash, food, LocalDate.of(2026, 1, 6), "15.00"); // stays unreconciled
    ReportFilter filter =
        new ReportFilter(
            FilterField.RECONCILIATION,
            FilterLevel.POSTING,
            FilterOperator.IS_ONE_OF,
            List.of("reconciled"));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void reconciliationFilterTransactionLevelAdmitsTheWholeTransactionIfAnyLegMatches() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long txn = insertTransaction(LocalDate.of(2026, 1, 5), false, false);
    long cashLeg = insertPosting(txn, cash, "-20.00", null);
    insertPosting(txn, food, "20.00", null); // the Food leg itself stays unreconciled
    setPostingReconciliation(cashLeg, "reconciled");
    ReportFilter filter =
        new ReportFilter(
            FilterField.RECONCILIATION,
            FilterLevel.TRANSACTION,
            FilterOperator.IS_ONE_OF,
            List.of("reconciled"));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    // The Food leg itself is unreconciled, but the Cash leg of the SAME transaction is — a
    // transaction-level filter admits the transaction, then the measure still sums the Food leg.
    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void noteFilterContainsIsCaseInsensitiveAndEscapesWildcards() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long matching = insertTransaction(LocalDate.of(2026, 1, 5), false, false, null, "50% off deal");
    insertPosting(matching, cash, "-20.00", null);
    insertPosting(matching, food, "20.00", null);
    long other = insertTransaction(LocalDate.of(2026, 1, 6), false, false, null, "unrelated");
    insertPosting(other, cash, "-15.00", null);
    insertPosting(other, food, "15.00", null);
    ReportFilter filter =
        new ReportFilter(
            FilterField.NOTE, FilterLevel.TRANSACTION, FilterOperator.CONTAINS, List.of("50% OFF"));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }

  @Test
  void noteFilterPostingLevelReadsThePostingsOwnNoteNotTheTransactions() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long food = insertAccount("Food", "expense", EUR, null);
    long txn = insertTransaction(LocalDate.of(2026, 1, 5), false, false, null, "unrelated");
    insertPosting(txn, cash, "-20.00", null);
    long foodLeg = insertPosting(txn, food, "20.00", null);
    setPostingNote(foodLeg, "gift for Bob");
    ReportFilter filter =
        new ReportFilter(
            FilterField.NOTE, FilterLevel.POSTING, FilterOperator.CONTAINS, List.of("gift"));

    List<RawTurnoverCell> cells =
        repository.totalTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false,
            new QueryConstraints(List.of(filter)));

    assertThat(cells).hasSize(1);
    amount(cells.get(0).nativeAmount(), "20.00");
  }
}
