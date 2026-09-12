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
import volkovandr.hauptbuch.analytics.repository.RawBalanceCell;
import volkovandr.hauptbuch.analytics.repository.RawTurnoverCell;
import volkovandr.hauptbuch.analytics.repository.ReportQueryRepository;
import volkovandr.hauptbuch.analytics.repository.TopLevelNode;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ReportQueryRepository} — the report engine's grouped reads.
 * Covers every measure×currency variant, the leg selections, cross-currency valuation under the
 * turnover rule (data-model §6.1), the tag double-tag dedup (§10.3), and the scope defaults
 * (reporting.md §6.4). Boots Spring so the query under test is the real repository SQL.
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

  private long insertTransaction(LocalDate date, boolean pendingReview, boolean deleted) {
    return jdbcClient
        .sql(
            """
            insert into transaction (date, lifecycle, deleted_at)
            values (:d, :l, case when :deleted then now() end)
            returning transaction_id
            """)
        .param("d", date)
        .param("l", pendingReview ? "pending_review" : "confirmed")
        .param("deleted", deleted)
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
            false);

    // Restaurants (a child of Food) rolls up under Food's top-level id — Food and its child are
    // one row (stage a renders fully collapsed; expansion is stage e).
    assertThat(cells).hasSize(2);
    amount(byLabelAndMonth(cells, "Food", "2026-01").nativeAmount(), "50.00");
    amount(byLabelAndMonth(cells, "Food", "2026-02").nativeAmount(), "5.00");
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
            false);
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
            false);
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
            false);

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
            false);

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
            false);

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
            false);
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
            true);
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
            false);
    assertThat(excludingClosed).isEmpty();

    List<RawTurnoverCell> includingClosed =
        repository.accountTreeTurnover(
            List.of("expense"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 31),
            EUR,
            "NET",
            true,
            false);
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
            false);

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
            false);

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
            false);

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
            ASSET_LIABILITY, LocalDate.of(2026, 1, 31), true, false);

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
                List.of("asset"), LocalDate.of(2026, 6, 1), false, false))
        .isEmpty();
    assertThat(
            repository.accountTreeClosingBalance(
                List.of("asset"), LocalDate.of(2026, 6, 1), true, false))
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
            List.of("asset"), LocalDate.of(2026, 1, 1), true, false);
    assertThat(excludingPending).hasSize(1);
    amount(byLabel(excludingPending, "Cash").nativeBalance(), "100.00");

    List<RawBalanceCell> includingPending =
        repository.accountTreeClosingBalance(
            List.of("asset"), LocalDate.of(2026, 1, 1), true, true);
    assertThat(includingPending).hasSize(1);
    amount(byLabel(includingPending, "Cash").nativeBalance(), "600.00");
  }

  @Test
  void totalClosingBalanceCollapsesEveryInScopeAccountIntoOneRowPerCurrency() {
    long cash = insertAccount("Cash", "asset", EUR, null);
    long card = insertAccount("Credit Card", "liability", EUR, null);
    long opening = insertAccount("Opening Balances", "equity", EUR, null);
    postSingleCurrency(opening, cash, LocalDate.of(2025, 1, 1), "100.00");
    postSingleCurrency(card, opening, LocalDate.of(2025, 1, 1), "20.00");

    List<RawBalanceCell> cells =
        repository.totalClosingBalance(ASSET_LIABILITY, LocalDate.of(2026, 1, 1), true, false);

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
                List.of("asset"), LocalDate.of(2026, 6, 1), false, false))
        .isEmpty();
    assertThat(
            repository.totalClosingBalance(List.of("asset"), LocalDate.of(2026, 6, 1), true, false))
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
  void topLevelTagsListsOnlyLiveTopLevelTags() {
    long car = insertTag("Car", null);
    insertTag("Audi", car); // not top-level — excluded
    long trip = insertTag("Trip", null);
    jdbcClient.sql("update tag set deleted_at = now() where tag_id = :t").param("t", trip).update();

    assertThat(repository.topLevelTags()).extracting(TopLevelNode::label).containsExactly("Car");
  }
}
