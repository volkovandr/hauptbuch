package volkovandr.hauptbuch.statements;

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
import volkovandr.hauptbuch.statements.repository.StatementMatchRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): the matcher's candidate, match and extras queries (statements.md
 * §4, §6.3) through the real {@link StatementMatchRepository}. Crafted books cover the asymmetric
 * date window and its edges, the account-leg amount, the payee substring, the wrong-account
 * exclusions (reconciled, person leaf, category, foreign currency), a cross-currency transaction,
 * the matched-elsewhere flag and the extras. Raw {@link JdbcClient} seeds the rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementCandidatesSqlLogicTest {

  private static final LocalDate BOOKING = LocalDate.of(2026, 5, 20);
  private static final int STATEMENT_LINES = 15;

  @Autowired JdbcClient jdbcClient;
  @Autowired StatementMatchRepository matcher;
  @Autowired StatementReviewService reviewService;

  private long own;
  private long otherEur;
  private long statementId;
  private long lineId;

  @BeforeEach
  void seed() {
    own = account("BankAaa-EUR", "asset", "EUR", false);
    otherEur = account("BankBbb-EUR", "asset", "EUR", false);
    long profile =
        id(
            "insert into statement_profile (name, format) values ('BankAaa CSV', 'csv')"
                + " returning statement_profile_id");
    statementId = statement(profile, own, "2026-05-01", "2026-05-31");
    lineId = line(statementId, 0, BOOKING, "-3.50", "SHOPAAA SAGT DANKE 4711", null);
  }

  // ---- seeding helpers ------------------------------------------------------------------

  private long id(String sql) {
    return jdbcClient.sql(sql).query(Long.class).single();
  }

  /** Scans of an index so far in this transaction (the class is {@code @Transactional}). */
  private long indexScans(long indexOid) {
    return id("select pg_stat_get_xact_numscans(" + indexOid + ")");
  }

  private long account(String name, String type, String currency, boolean personLeaf) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, person_leaf)"
                + " values (:n, :t, :c, :p) returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currency)
        .param("p", personLeaf)
        .query(Long.class)
        .single();
  }

  private long statement(long profile, long account, String start, String end) {
    return jdbcClient
        .sql(
            "insert into statement (statement_profile_id, account_id, state, original_filename,"
                + " file_path, period_start, period_end)"
                + " values (:p, :a, 'new', 'f.csv', 'x', :s, :e) returning statement_id")
        .param("p", profile)
        .param("a", account)
        .param("s", LocalDate.parse(start))
        .param("e", LocalDate.parse(end))
        .query(Long.class)
        .single();
  }

  private long line(
      long statement,
      int order,
      LocalDate date,
      String amount,
      String counterparty,
      String problem) {
    return jdbcClient
        .sql(
            "insert into statement_line (statement_id, sort_order, booking_date, amount,"
                + " counterparty, problem) values (:s, :o, :d, :a, :c, :p)"
                + " returning statement_line_id")
        .param("s", statement)
        .param("o", order)
        .param("d", date)
        .param("a", new BigDecimal(amount))
        .param("c", counterparty)
        .param("p", problem)
        .query(Long.class)
        .single();
  }

  private long payee(String name) {
    return jdbcClient
        .sql("insert into payee (name) values (:n) returning payee_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  private long transaction(LocalDate date, Long payeeId, boolean deleted) {
    return jdbcClient
        .sql(
            "insert into transaction (date, payee_id, deleted_at)"
                + " values (:d, :p, case when :x then now() end) returning transaction_id")
        .param("d", date)
        .param("p", payeeId)
        .param("x", deleted)
        .query(Long.class)
        .single();
  }

  private long posting(long transaction, long account, String amount, String reconciliation) {
    return jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation)"
                + " values (:t, :a, :m, :r) returning posting_id")
        .param("t", transaction)
        .param("a", account)
        .param("m", new BigDecimal(amount))
        .param("r", reconciliation)
        .query(Long.class)
        .single();
  }

  /** A two-leg transaction on {@code account} against a fresh expense category; returns the leg. */
  private long expense(LocalDate date, long account, String amount, Long payeeId) {
    long category = account("Food-" + date + amount, "expense", "EUR", false);
    long txn = transaction(date, payeeId, false);
    long leg = posting(txn, account, amount, "unreconciled");
    posting(txn, category, new BigDecimal(amount).negate().toPlainString(), "unreconciled");
    return leg;
  }

  private void match(long statement, long line, long posting) {
    jdbcClient
        .sql(
            "insert into statement_match (statement_line_id, statement_id, posting_id)"
                + " values (:l, :s, :p)")
        .param("l", line)
        .param("s", statement)
        .param("p", posting)
        .update();
    jdbcClient
        .sql("update posting set reconciliation = 'reconciled' where posting_id = :p")
        .param("p", posting)
        .update();
  }

  private List<Long> candidatePostings() {
    return matcher.findCandidates(statementId).stream().map(StatementCandidate::postingId).toList();
  }

  // ---- the date window ------------------------------------------------------------------

  @Test
  void windowIsTenDaysBeforeAndThreeAfterTheBookingDateInclusive() {
    long tenBefore = expense(BOOKING.minusDays(10), own, "-3.50", null);
    long elevenBefore = expense(BOOKING.minusDays(11), own, "-3.50", null);
    long threeAfter = expense(BOOKING.plusDays(3), own, "-3.50", null);
    long fourAfter = expense(BOOKING.plusDays(4), own, "-3.50", null);

    assertThat(candidatePostings())
        .contains(tenBefore, threeAfter)
        .doesNotContain(elevenBefore, fourAfter);
  }

  @Test
  void theProfilesOwnWindowApplies() {
    jdbcClient
        .sql("update statement_profile set window_days_before = 2, window_days_after = 0")
        .update();
    long inside = expense(BOOKING.minusDays(2), own, "-3.50", null);
    long tooEarly = expense(BOOKING.minusDays(3), own, "-3.50", null);
    long tooLate = expense(BOOKING.plusDays(1), own, "-3.50", null);

    assertThat(candidatePostings()).contains(inside).doesNotContain(tooEarly, tooLate);
  }

  @Test
  void reportsTheDistanceInDaysAndExcludesDeletedTransactions() {
    long near = expense(BOOKING.minusDays(2), own, "-3.50", null);
    long txn = transaction(BOOKING, null, true);
    long deleted = posting(txn, own, "-3.50", "unreconciled");

    assertThat(candidatePostings()).contains(near).doesNotContain(deleted);
    assertThat(matcher.findCandidates(statementId))
        .filteredOn(c -> c.postingId() == near)
        .singleElement()
        .satisfies(c -> assertThat(c.dayDistance()).isEqualTo(2));
  }

  // ---- amount and payee on the statement's own account -----------------------------------

  @Test
  void equalAmountIsCandidateWithoutPayee() {
    long equal = expense(BOOKING.minusDays(1), own, "-3.50", null);

    assertThat(matcher.findCandidates(statementId))
        .filteredOn(c -> c.postingId() == equal)
        .singleElement()
        .satisfies(c -> assertThat(c.payeeSimilar()).isFalse());
  }

  @Test
  void differentAmountIsCandidateOnlyWithSimilarPayee() {
    long similar = expense(BOOKING.minusDays(1), own, "-3.80", payee("ShopAaa"));
    long unrelated = expense(BOOKING.minusDays(1), own, "-3.80", payee("ShopZzz"));
    long noPayee = expense(BOOKING.minusDays(1), own, "-3.80", null);

    assertThat(candidatePostings()).contains(similar).doesNotContain(unrelated, noPayee);
  }

  @Test
  void payeeSimilarityIsCaseInsensitiveSubstringOfCounterpartyOrDescription() {
    long upper = expense(BOOKING, own, "-9.00", payee("shopaaa"));
    long inDescription = line(statementId, 1, BOOKING, "-9.00", null, null);
    jdbcClient
        .sql(
            "update statement_line set description = 'CARD PAYMENT ShopBbb 12' where"
                + " statement_line_id = :l")
        .param("l", inDescription)
        .update();
    long shopBbb = expense(BOOKING, own, "-9.10", payee("ShopBbb"));
    long reversed = expense(BOOKING, own, "-9.10", payee("ShopAaa SAGT DANKE 4711 EXTRA"));

    List<StatementCandidate> all = matcher.findCandidates(statementId);

    assertThat(all)
        .filteredOn(c -> c.postingId() == upper && c.statementLineId() == lineId)
        .singleElement()
        .satisfies(c -> assertThat(c.payeeSimilar()).isTrue());
    assertThat(all)
        .filteredOn(c -> c.postingId() == shopBbb && c.statementLineId() == inDescription)
        .hasSize(1);
    assertThat(all).filteredOn(c -> c.postingId() == reversed).isEmpty();
  }

  @Test
  void theAmountIsTheSumOfTheAccountsLegsInTheTransaction() {
    long category = account("Food", "expense", "EUR", false);
    long txn = transaction(BOOKING, null, false);
    posting(txn, own, "-1.50", "unreconciled");
    posting(txn, own, "-2.00", "unreconciled");
    posting(txn, category, "3.50", "unreconciled");

    assertThat(matcher.findCandidates(statementId))
        .singleElement()
        .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("-3.50"));
  }

  @Test
  void crossCurrencyTransactionOffersTheStatementAccountsNativeLeg() {
    long usd = account("BankCcc-USD", "asset", "USD", false);
    long txn = transaction(BOOKING, null, false);
    long ownLeg = posting(txn, own, "-3.50", "unreconciled");
    long usdLeg = posting(txn, usd, "4.00", "unreconciled");

    assertThat(candidatePostings()).contains(ownLeg).doesNotContain(usdLeg);
  }

  @Test
  void reconciledLegsAreStillCandidatesOnTheStatementsOwnAccount() {
    long txn = transaction(BOOKING, null, false);
    long leg = posting(txn, own, "-3.50", "reconciled");
    posting(txn, account("Food", "expense", "EUR", false), "3.50", "unreconciled");

    assertThat(candidatePostings()).contains(leg);
  }

  // ---- the wrong-account tier -----------------------------------------------------------

  @Test
  void wrongAccountCandidateNeedsEqualAmountSimilarPayeeAndNoReconciliation() {
    long shop = payee("ShopAaa");
    long wrong = expense(BOOKING, otherEur, "-3.50", shop);
    long differentAmount = expense(BOOKING, otherEur, "-3.60", shop);
    long noPayee = expense(BOOKING, otherEur, "-3.50", null);
    long reconciledElsewhere = expense(BOOKING, otherEur, "-3.50", shop);
    jdbcClient
        .sql("update posting set reconciliation = 'reconciled' where posting_id = :p")
        .param("p", reconciledElsewhere)
        .update();
    long clearedElsewhere = expense(BOOKING, otherEur, "-3.50", shop);
    jdbcClient
        .sql("update posting set reconciliation = 'cleared' where posting_id = :p")
        .param("p", clearedElsewhere)
        .update();

    assertThat(candidatePostings())
        .contains(wrong, clearedElsewhere)
        .doesNotContain(differentAmount, noPayee, reconciledElsewhere);
  }

  @Test
  void wrongAccountCandidatesExcludePersonLeavesCategoriesDeletedAndForeignCurrency() {
    long shop = payee("ShopAaa");
    long person = account("Debt-Doe", "asset", "EUR", true);
    long usd = account("BankCcc-USD", "asset", "USD", false);
    long deletedAccount = account("BankDdd-EUR", "asset", "EUR", false);
    jdbcClient
        .sql("update account set deleted_at = now() where account_id = :a")
        .param("a", deletedAccount)
        .update();
    long onPerson = expense(BOOKING, person, "-3.50", shop);
    long onUsd = expense(BOOKING, usd, "-3.50", shop);
    long onDeleted = expense(BOOKING, deletedAccount, "-3.50", shop);
    long txn = transaction(BOOKING, shop, false);
    long onCategory =
        posting(txn, account("Food", "expense", "EUR", false), "-3.50", "unreconciled");
    posting(txn, own, "3.50", "unreconciled");

    assertThat(candidatePostings()).doesNotContain(onPerson, onUsd, onDeleted, onCategory);
  }

  // ---- matches, overlaps and exclusivity inputs ------------------------------------------

  @Test
  void legMatchedToAnotherLineOfThisStatementIsNotCandidateAgain() {
    long taken = expense(BOOKING, own, "-3.50", null);
    long other = line(statementId, 1, BOOKING, "-3.50", "SHOPAAA", null);
    match(statementId, other, taken);

    assertThat(candidatePostings()).doesNotContain(taken);
  }

  @Test
  void matchedLineGetsNoCandidates() {
    long taken = expense(BOOKING, own, "-3.50", null);
    expense(BOOKING, own, "-3.50", null);
    match(statementId, lineId, taken);

    assertThat(matcher.findCandidates(statementId)).isEmpty();
  }

  @Test
  void legMatchedOnAnotherStatementIsFlaggedAsMatchedElsewhere() {
    long profile = id("select min(statement_profile_id) from statement_profile");
    long earlier = statement(profile, own, "2026-04-15", "2026-05-15");
    long earlierLine = line(earlier, 0, BOOKING.minusDays(2), "-3.50", "SHOPAAA", null);
    long leg = expense(BOOKING, own, "-3.50", null);
    long fresh = expense(BOOKING, own, "-3.50", null);
    match(earlier, earlierLine, leg);

    List<StatementCandidate> candidates = matcher.findCandidates(statementId);

    assertThat(candidates)
        .filteredOn(c -> c.postingId() == leg)
        .singleElement()
        .satisfies(c -> assertThat(c.matchedElsewhere()).isTrue());
    assertThat(candidates)
        .filteredOn(c -> c.postingId() == fresh)
        .singleElement()
        .satisfies(c -> assertThat(c.matchedElsewhere()).isFalse());
  }

  @Test
  void legExcludedFromLineIsNotProposedToItAgain() {
    long excluded = expense(BOOKING, own, "-3.50", null);
    long other = expense(BOOKING, own, "-3.50", null);
    matcher.insertExclusion(lineId, excluded);

    assertThat(candidatePostings()).contains(other).doesNotContain(excluded);
  }

  @Test
  void problemUndatedOrAmountlessLinesGetNoCandidates() {
    jdbcClient
        .sql("delete from statement_line where statement_line_id = :l")
        .param("l", lineId)
        .update();
    line(statementId, 1, BOOKING, "-3.50", "SHOPAAA", "Currency USD, but the account is in EUR");
    long undated = line(statementId, 2, BOOKING, "-3.50", "SHOPAAA", null);
    jdbcClient
        .sql("update statement_line set booking_date = null where statement_line_id = :l")
        .param("l", undated)
        .update();
    expense(BOOKING, own, "-3.50", null);

    assertThat(matcher.findCandidates(statementId)).isEmpty();
  }

  @Test
  void findMatchesReturnsTheMatchedLegWithItsTransaction() {
    long shop = payee("ShopAaa");
    long leg = expense(BOOKING.minusDays(1), own, "-3.50", shop);
    match(statementId, lineId, leg);

    assertThat(matcher.findMatches(statementId))
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.statementLineId()).isEqualTo(lineId);
              assertThat(m.postingId()).isEqualTo(leg);
              assertThat(m.payeeName()).isEqualTo("ShopAaa");
              assertThat(m.transactionDate()).isEqualTo(BOOKING.minusDays(1));
              assertThat(m.reconciliation()).isEqualTo("reconciled");
            });
  }

  // ---- extras ---------------------------------------------------------------------------

  @Test
  void extrasAreUnreconciledUnmatchedLegsOnTheAccountDatedInThePeriod() {
    final long inPeriod = expense(LocalDate.of(2026, 5, 15), own, "-1.00", null);
    final long onFirst = expense(LocalDate.of(2026, 5, 1), own, "-1.00", null);
    final long onLast = expense(LocalDate.of(2026, 5, 31), own, "-1.00", null);
    long cleared = expense(LocalDate.of(2026, 5, 16), own, "-1.00", null);
    jdbcClient
        .sql("update posting set reconciliation = 'cleared' where posting_id = :p")
        .param("p", cleared)
        .update();
    long before = expense(LocalDate.of(2026, 4, 30), own, "-1.00", null);
    long after = expense(LocalDate.of(2026, 6, 1), own, "-1.00", null);
    long reconciled = expense(LocalDate.of(2026, 5, 17), own, "-1.00", null);
    jdbcClient
        .sql("update posting set reconciliation = 'reconciled' where posting_id = :p")
        .param("p", reconciled)
        .update();
    long otherAccount = expense(LocalDate.of(2026, 5, 18), otherEur, "-1.00", null);
    long deletedTxn = transaction(LocalDate.of(2026, 5, 19), null, true);
    long deleted = posting(deletedTxn, own, "-1.00", "unreconciled");
    long matchedHere = expense(LocalDate.of(2026, 5, 20), own, "-1.00", null);
    match(statementId, lineId, matchedHere);

    assertThat(matcher.findExtras(statementId))
        .extracting(StatementExtra::postingId)
        .containsExactly(onFirst, inPeriod, cleared, onLast)
        .doesNotContain(before, after, reconciled, otherAccount, deleted, matchedHere);
  }

  @Test
  void legReconciledOnAnotherStatementIsNeverExtra() {
    long profile = id("select min(statement_profile_id) from statement_profile");
    long june = statement(profile, own, "2026-06-01", "2026-06-30");
    long juneLine = line(june, 0, LocalDate.of(2026, 6, 2), "-1.00", "SHOPAAA", null);
    long leg = expense(LocalDate.of(2026, 5, 30), own, "-1.00", null);
    match(june, juneLine, leg);

    assertThat(matcher.findExtras(statementId)).isEmpty();
  }

  @Test
  void statementWithoutPeriodHasNoExtras() {
    jdbcClient
        .sql("update statement set period_start = null, period_end = null where statement_id = :s")
        .param("s", statementId)
        .update();
    expense(LocalDate.of(2026, 5, 15), own, "-1.00", null);

    assertThat(matcher.findExtras(statementId)).isEmpty();
  }

  // ---- line-scoped candidates (statements/issues/15) ---------------------------------------

  @Test
  void lineScopedCandidatesAreExactlyThatLinesRowsOfTheFullQuery() {
    final long second = line(statementId, 1, BOOKING.plusDays(1), "-7.00", "SHOPBBB", null);
    expense(BOOKING.minusDays(1), own, "-3.50", null);
    expense(BOOKING.plusDays(1), own, "-7.00", null);
    expense(BOOKING, own, "-3.50", payee("ShopAaa"));

    List<StatementCandidate> full = matcher.findCandidates(statementId);

    assertThat(matcher.findCandidatesForLine(statementId, lineId))
        .isNotEmpty()
        .containsExactlyElementsOf(
            full.stream().filter(c -> c.statementLineId() == lineId).toList());
    assertThat(matcher.findCandidatesForLine(statementId, second))
        .isNotEmpty()
        .containsExactlyElementsOf(
            full.stream().filter(c -> c.statementLineId() == second).toList());
  }

  @Test
  void lineScopedCandidatesAreEmptyOnceMatched() {
    long taken = expense(BOOKING, own, "-3.50", null);
    match(statementId, lineId, taken);

    assertThat(matcher.findCandidatesForLine(statementId, lineId)).isEmpty();
  }

  @Test
  void rivalCandidatesAreTheOtherLinesRowsOnTheGivenPostingsOnly() {
    long rival = line(statementId, 1, BOOKING.plusDays(1), "-3.50", "SHOPAAA", null);
    long shared = expense(BOOKING, own, "-3.50", null);
    long unrelated = expense(BOOKING.plusDays(1), own, "-3.50", null);

    List<StatementCandidate> rivals =
        matcher.findCandidatesOnPostings(statementId, lineId, List.of(shared));

    assertThat(rivals)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.statementLineId()).isEqualTo(rival);
              assertThat(c.postingId()).isEqualTo(shared);
            });
    assertThat(rivals).extracting(StatementCandidate::postingId).doesNotContain(unrelated);
  }

  @Test
  void rivalCandidatesAreEmptyWithoutPostings() {
    assertThat(matcher.findCandidatesOnPostings(statementId, lineId, List.of())).isEmpty();
  }

  @Test
  void reviewingOneLineGivesTheSameLineReviewAsReviewingTheStatement() {
    final long closer = line(statementId, 1, BOOKING.plusDays(1), "-3.50", "SHOPAAA", null);
    final long third = line(statementId, 2, BOOKING.plusDays(2), "-7.00", "SHOPBBB", null);
    expense(BOOKING, own, "-3.50", payee("ShopAaa"));
    expense(BOOKING.plusDays(1), own, "-3.50", null);
    expense(BOOKING.plusDays(2), own, "-7.00", null);

    List<LineReview> full = reviewService.review(statementId).lines();

    assertThat(full).hasSize(3);
    for (long id : List.of(lineId, closer, third)) {
      assertThat(reviewService.reviewLine(statementId, id))
          .contains(full.stream().filter(l -> l.line().statementLineId() == id).findFirst().get());
    }
  }

  // ---- the plan (statements/issues/16) -----------------------------------------------------

  @Test
  void candidateQueriesWalkTheDateIndexInsteadOfScanningTheRegister() {
    // A register shaped like the owner's (~28k transactions, ~100 accounts, a few hundred payees
    // and statement lines): the planner only starts from the line windows when the query forces it.
    jdbcClient
        .sql(
            "insert into account (name, type, currency_code)"
                + " select 'Bank' || g, 'asset', 'EUR' from generate_series(1, 5) g")
        .update();
    jdbcClient
        .sql(
            "insert into account (name, type, currency_code)"
                + " select 'Cat' || g, 'expense', 'EUR' from generate_series(1, 80) g")
        .update();
    jdbcClient
        .sql("insert into payee (name) select 'Shop' || g from generate_series(1, 300) g")
        .update();
    jdbcClient
        .sql(
            "insert into transaction (date, payee_id)"
                + " select date '2019-01-01' + (g * 37 % 2850),"
                + " (select array_agg(payee_id) from payee)[1 + g % 300]"
                + " from generate_series(1, 28000) g")
        .update();
    jdbcClient
        .sql(
            "with banks as (select array_agg(account_id) as ids from account"
                + " where name like 'Bank%' and account_id not in (:own, :other)),"
                + " cats as (select array_agg(account_id) as ids from account"
                + " where name like 'Cat%')"
                + " insert into posting (transaction_id, account_id, amount)"
                + " select t.transaction_id, banks.ids[1 + t.transaction_id % 5], -1"
                + " from transaction t, banks"
                + " union all select t.transaction_id, cats.ids[1 + t.transaction_id % 80], 1"
                + " from transaction t, cats")
        .param("own", own)
        .param("other", otherEur)
        .update();
    long profile = id("select min(statement_profile_id) from statement_profile");
    for (int n = 0; n < 24; n++) {
      long other = statement(profile, own, "2025-01-01", "2025-01-20");
      jdbcClient
          .sql(
              "insert into statement_line (statement_id, sort_order, booking_date, amount)"
                  + " select :s, g, date '2025-01-01' + g, -1 from generate_series(1, 15) g")
          .param("s", other)
          .update();
    }
    for (int order = 1; order < STATEMENT_LINES; order++) {
      line(statementId, order, BOOKING.plusDays(order * 2L), "-3.50", "SHOPAAA", null);
    }
    for (String table : List.of("transaction", "posting", "account", "payee", "statement_line")) {
      jdbcClient.sql("analyze " + table).update();
    }
    long indexId = id("select 'transaction_date_idx'::regclass::oid::bigint");
    long before = indexScans(indexId);

    matcher.findCandidates(statementId);

    assertThat(indexScans(indexId) - before)
        .as("the date window is applied through transaction_date_idx, once per line")
        .isGreaterThanOrEqualTo(STATEMENT_LINES);
  }

  @Test
  void rivalCandidatesIgnoreTheDateWindowsOfOtherLines() {
    long farLine = line(statementId, 1, BOOKING.plusDays(40), "-9.00", "SHOPCCC", null);
    long shared = expense(BOOKING, own, "-3.50", null);
    expense(BOOKING.plusDays(40), own, "-9.00", null);

    assertThat(matcher.findCandidatesOnPostings(statementId, lineId, List.of(shared)))
        .extracting(StatementCandidate::statementLineId)
        .doesNotContain(farLine);
  }
}
