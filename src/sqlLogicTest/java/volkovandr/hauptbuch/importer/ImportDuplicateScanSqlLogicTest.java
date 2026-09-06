package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

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
import volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ImportDuplicateScanRepository#rescan} — the commit-time
 * ledger duplicate scan (import.md §9; plan f1), exercised through its only entry point. The logic
 * lives in the SQL: the detection spans staging → the live ledger on date + funding account +
 * amount + category (with the semantic-category → currency-leaf parent test), and the reconcile is
 * data-modifying CTEs that drop / add / re-raise the persisted matches — never silently losing a
 * decision the owner made against a since-changed ledger transaction (Q-IMP-5).
 *
 * <p>Crafted staging and ledger rows via raw {@link JdbcClient}; the query under test is the real
 * repository. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportDuplicateScanSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired ImportDuplicateScanRepository repository;

  // ── the detection ───────────────────────────────────────────────────────

  @Test
  void findsReimportedTransactionMatchingOnDateFundingAccountAmountAndCategory() {
    final long session = openSession();
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    fundingLeg(staged, "Current", "-12.34");
    categoryLeg(staged, "Food", "12.34");

    long ledgerTxn = ledgerTransaction("2024-03-01");
    posting(ledgerTxn, giro, "-12.34");
    posting(ledgerTxn, food, "12.34");

    assertThat(scan(session))
        .singleElement()
        .extracting(ImportDuplicateMatch::importTransactionId, ImportDuplicateMatch::transactionId)
        .containsExactly(staged, ledgerTxn);
  }

  @Test
  void matchesTheCurrencyLeafBeneathTheMappedSemanticCategory() {
    final long session = openSession();
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);
    final long foodEur = account("Food-EUR", "expense", food);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    fundingLeg(staged, "Current", "-5.00");
    categoryLeg(staged, "Food", "5.00");

    long ledgerTxn = ledgerTransaction("2024-03-01");
    posting(ledgerTxn, giro, "-5.00");
    posting(ledgerTxn, foodEur, "5.00"); // the ledger posted to the leaf, not the semantic node

    assertThat(scan(session))
        .extracting(ImportDuplicateMatch::transactionId)
        .containsExactly(ledgerTxn);
  }

  @Test
  void matchesReimportedCrossCurrencyTransferOnTheResolvedNativeFarAmount() {
    final long session = openSession();
    long current = account("Current", "asset", null, "EUR");
    long foreign = account("Foreign", "asset", null, "CHF");
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", current);
    mapAccount(session, "Foreign", foreign);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    // The EUR file states only its own side; the mirror supplied the real far amount as
    // counter_amount, leaving `amount` as the funding-currency view of the transfer leg (import.md
    // §6). The ledger stores the mirror image — native `amount`, base-valued `base_amount`.
    fundingLeg(staged, "Current", "100.00");
    transferLeg(staged, "Foreign", "-100.00", "-92.50");

    long ledgerTxn = ledgerTransaction("2024-03-01");
    posting(ledgerTxn, current, "100.00", "100.00");
    posting(ledgerTxn, foreign, "-92.50", "-100.00");

    assertThat(scan(session))
        .singleElement()
        .extracting(ImportDuplicateMatch::importTransactionId, ImportDuplicateMatch::transactionId)
        .containsExactly(staged, ledgerTxn);
  }

  @Test
  void matchesReimportedSameCurrencyTransferThatCarriesNoCounterAmount() {
    final long session = openSession();
    long current = account("Current", "asset", null);
    long savings = account("Savings", "asset", null);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", current);
    mapAccount(session, "Savings", savings);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    fundingLeg(staged, "Current", "-250.00");
    transferLeg(staged, "Savings", "250.00", null);

    long ledgerTxn = ledgerTransaction("2024-03-01");
    posting(ledgerTxn, current, "-250.00");
    posting(ledgerTxn, savings, "250.00");

    assertThat(scan(session))
        .extracting(ImportDuplicateMatch::transactionId)
        .containsExactly(ledgerTxn);
  }

  @Test
  void noMatchWhenDateAmountOrCategoryDiffers() {
    final long session = openSession();
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);
    final long fun = account("Fun", "expense", null);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    fundingLeg(staged, "Current", "-12.34");
    categoryLeg(staged, "Food", "12.34");

    long wrongDate = ledgerTransaction("2024-03-02");
    posting(wrongDate, giro, "-12.34");
    posting(wrongDate, food, "12.34");
    long wrongAmount = ledgerTransaction("2024-03-01");
    posting(wrongAmount, giro, "-99.99");
    posting(wrongAmount, food, "99.99");
    long wrongCategory = ledgerTransaction("2024-03-01");
    posting(wrongCategory, giro, "-12.34");
    posting(wrongCategory, fun, "12.34");

    assertThat(scan(session)).isEmpty();
  }

  @Test
  void ignoresStagedRowsThatWillNotBook() {
    final long session = openSession();
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long ledgerTxn = ledgerTransaction("2024-03-01");
    posting(ledgerTxn, giro, "-7.00");
    posting(ledgerTxn, food, "7.00");

    for (String nonBooking : new String[] {"parked", "mirrored", "excluded"}) {
      long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
      jdbcClient
          .sql("update import_transaction set state = :s where import_transaction_id = :id")
          .param("s", nonBooking)
          .param("id", staged)
          .update();
      fundingLeg(staged, "Current", "-7.00");
      categoryLeg(staged, "Food", "7.00");
    }
    long openingBalance = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    jdbcClient
        .sql(
            "update import_transaction set opening_balance = true"
                + " where import_transaction_id = :id")
        .param("id", openingBalance)
        .update();
    fundingLeg(openingBalance, "Current", "-7.00");
    categoryLeg(openingBalance, "Food", "7.00");

    assertThat(scan(session)).isEmpty();
  }

  @Test
  void ignoresSoftDeletedLedgerTransactions() {
    final long session = openSession();
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    fundingLeg(staged, "Current", "-8.00");
    categoryLeg(staged, "Food", "8.00");

    long ledgerTxn = ledgerTransaction("2024-03-01");
    posting(ledgerTxn, giro, "-8.00");
    posting(ledgerTxn, food, "8.00");
    jdbcClient
        .sql("update transaction set deleted_at = now() where transaction_id = :id")
        .param("id", ledgerTxn)
        .update();

    assertThat(scan(session)).isEmpty();
  }

  @Test
  void scopesToTheGivenSessionAndReturnsBothLedgerHitsForOneStagedRow() {
    final long session = openSession();
    long other = openOtherSession();
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);

    long otherFile = stageFile(other, "Current");
    mapAccount(other, "Current", giro);
    mapCategory(other, "Food", food);
    long otherStaged = stageTransaction(otherFile, LocalDate.of(2024, 3, 1));
    fundingLeg(otherStaged, "Current", "-3.00");
    categoryLeg(otherStaged, "Food", "3.00");

    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long staged = stageTransaction(fileId, LocalDate.of(2024, 3, 1));
    fundingLeg(staged, "Current", "-3.00");
    categoryLeg(staged, "Food", "3.00");

    long hitOne = ledgerTransaction("2024-03-01");
    posting(hitOne, giro, "-3.00");
    posting(hitOne, food, "3.00");
    long hitTwo = ledgerTransaction("2024-03-01");
    posting(hitTwo, giro, "-3.00");
    posting(hitTwo, food, "3.00");

    assertThat(scan(session))
        .extracting(ImportDuplicateMatch::importTransactionId)
        .containsOnly(staged);
    assertThat(scan(session))
        .extracting(ImportDuplicateMatch::transactionId)
        .containsExactlyInAnyOrder(hitOne, hitTwo);
  }

  // ── the re-runnable snapshot ────────────────────────────────────────────

  @Test
  void rescanPersistsMatchesAsPendingAndIsIdempotent() {
    final long session = openSession();
    long staged = seedSingleOverlap(session, "2024-03-01", "-10.00");

    assertThat(repository.rescan(session)).isEqualTo(1);
    assertThat(repository.rescan(session)).isEqualTo(1);

    assertThat(matchRows(session))
        .singleElement()
        .extracting(ImportDuplicateMatch::importTransactionId, ImportDuplicateMatch::adjudication)
        .containsExactly(staged, "pending");
  }

  @Test
  void rescanDropsMatchThatNoLongerOverlaps() {
    final long session = openSession();
    seedSingleOverlap(session, "2024-03-01", "-10.00");
    repository.rescan(session);

    // The owner voids the ledger transaction — the overlap is gone.
    jdbcClient.sql("update transaction set deleted_at = now()").update();

    assertThat(repository.rescan(session)).isZero();
    assertThat(matchRows(session)).isEmpty();
  }

  @Test
  void rescanKeepsAnAdjudicationWhoseLedgerTransactionHasNotChanged() {
    final long session = openSession();
    seedSingleOverlap(session, "2024-03-01", "-10.00");
    repository.rescan(session);
    long scanId = repository.findScan(session).orElseThrow().importDuplicateScanId();
    long matchId = matchRows(session).get(0).importDuplicateMatchId();

    assertThat(repository.adjudicate(scanId, matchId, "skip")).isTrue();

    repository.rescan(session);
    assertThat(matchRows(session).get(0).adjudication()).isEqualTo("skip");
  }

  @Test
  void rescanReraisesStaleAdjudicationOnRerun() {
    final long session = openSession();
    seedSingleOverlap(session, "2024-03-01", "-10.00");
    repository.rescan(session);
    long scanId = repository.findScan(session).orElseThrow().importDuplicateScanId();
    ImportDuplicateMatch match = matchRows(session).get(0);
    repository.adjudicate(scanId, match.importDuplicateMatchId(), "skip");

    // The owner edits that ledger transaction after deciding — updated_at moves on, but the pair
    // still overlaps.
    bumpUpdatedAt(match.transactionId());

    repository.rescan(session);
    assertThat(matchRows(session).get(0).adjudication()).isEqualTo("pending");
  }

  @Test
  void rescanReraisesRatherThanDroppingAnAdjudicationWhenLedgerNoLongerMatches() {
    final long session = openSession();
    seedSingleOverlap(session, "2024-03-01", "-10.00");
    repository.rescan(session);
    long scanId = repository.findScan(session).orElseThrow().importDuplicateScanId();
    ImportDuplicateMatch match = matchRows(session).get(0);
    repository.adjudicate(scanId, match.importDuplicateMatchId(), "skip");

    // The owner corrects a typo in the ledger transaction's amount — it no longer matches the
    // staged row, so the fresh detection drops it. The decision must be re-raised, not lost, or the
    // staged row silently books at f2 as a true duplicate.
    jdbcClient
        .sql("update posting set amount = -11.00 where transaction_id = :id and amount = -10.00")
        .param("id", match.transactionId())
        .update();
    bumpUpdatedAt(match.transactionId());

    assertThat(repository.rescan(session)).isEqualTo(1);
    assertThat(matchRows(session))
        .singleElement()
        .extracting(
            ImportDuplicateMatch::importDuplicateMatchId, ImportDuplicateMatch::adjudication)
        .containsExactly(match.importDuplicateMatchId(), "pending");
  }

  @Test
  void reAdjudicatedOrphanClearsOnceItsLedgerTransactionIsSettledAgain() {
    final long session = openSession();
    seedSingleOverlap(session, "2024-03-01", "-10.00");
    repository.rescan(session);
    long scanId = repository.findScan(session).orElseThrow().importDuplicateScanId();
    ImportDuplicateMatch match = matchRows(session).get(0);
    repository.adjudicate(scanId, match.importDuplicateMatchId(), "skip");
    jdbcClient
        .sql("update posting set amount = -11.00 where transaction_id = :id and amount = -10.00")
        .param("id", match.transactionId())
        .update();
    bumpUpdatedAt(match.transactionId());
    repository.rescan(session); // re-raised to pending

    // The owner looks again and confirms skip against the now-current transaction.
    repository.adjudicate(scanId, match.importDuplicateMatchId(), "skip");

    assertThat(repository.rescan(session)).isZero();
    assertThat(matchRows(session)).isEmpty();
  }

  @Test
  void latestLedgerMutationIsEmptyWithNoTransactions() {
    assertThat(repository.latestLedgerMutation()).isEmpty();
    ledgerTransaction("2024-03-01");
    assertThat(repository.latestLedgerMutation()).isPresent();
  }

  @Test
  void clearScanDiscardsTheSnapshotAndItsMatches() {
    final long session = openSession();
    seedSingleOverlap(session, "2024-03-01", "-10.00");
    repository.rescan(session);
    assertThat(repository.findScan(session)).isPresent();

    repository.clearScan(session);

    assertThat(repository.findScan(session)).isEmpty();
    assertThat(
            jdbcClient
                .sql("select count(*) from import_duplicate_match")
                .query(Integer.class)
                .single())
        .isZero();
  }

  // ── seeding helpers ─────────────────────────────────────────────────────

  private List<ImportDuplicateMatch> scan(long session) {
    repository.rescan(session);
    return matchRows(session);
  }

  private List<ImportDuplicateMatch> matchRows(long session) {
    return repository.findScan(session).stream()
        .flatMap(s -> repository.findMatchRows(s.importDuplicateScanId()).stream())
        .toList();
  }

  private void bumpUpdatedAt(long transactionId) {
    jdbcClient
        .sql(
            "update transaction set updated_at = now() + interval '1 second'"
                + " where transaction_id = :id")
        .param("id", transactionId)
        .update();
  }

  private long seedSingleOverlap(long session, String date, String amount) {
    long giro = account("Giro", "asset", null);
    long food = account("Food", "expense", null);
    final long fileId = stageFile(session, "Current");
    mapAccount(session, "Current", giro);
    mapCategory(session, "Food", food);
    long staged = stageTransaction(fileId, LocalDate.parse(date));
    fundingLeg(staged, "Current", amount);
    categoryLeg(staged, "Food", new BigDecimal(amount).negate().toPlainString());
    long ledgerTxn = ledgerTransaction(date);
    posting(ledgerTxn, giro, amount);
    posting(ledgerTxn, food, new BigDecimal(amount).negate().toPlainString());
    return staged;
  }

  private long openSession() {
    return jdbcClient
        .sql("insert into import_session (state) values ('open') returning import_session_id")
        .query(Long.class)
        .single();
  }

  private long openOtherSession() {
    return jdbcClient
        .sql("insert into import_session (state) values ('committed') returning import_session_id")
        .query(Long.class)
        .single();
  }

  private long account(String name, String type, Long parentId) {
    return account(name, type, parentId, "EUR");
  }

  private long account(String name, String type, Long parentId, String currencyCode) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code, parent_id)"
                + " values (:n, :t, :c, :p) returning account_id")
        .param("n", name)
        .param("t", type)
        .param("c", currencyCode)
        .param("p", parentId)
        .query(Long.class)
        .single();
  }

  private void mapAccount(long sessionId, String moneyAccountName, Long accountId) {
    jdbcClient
        .sql(
            """
            insert into import_account (import_session_id, money_account_name, account_id)
            values (:s, :n, :a)
            on conflict (import_session_id, money_account_name)
              do update set account_id = excluded.account_id
            """)
        .param("s", sessionId)
        .param("n", moneyAccountName)
        .param("a", accountId)
        .update();
  }

  private void mapCategory(long sessionId, String moneyPath, Long accountId) {
    jdbcClient
        .sql(
            """
            insert into import_category (import_session_id, money_path, account_id)
            values (:s, :p, :a)
            on conflict (import_session_id, money_path)
              do update set account_id = excluded.account_id
            """)
        .param("s", sessionId)
        .param("p", moneyPath)
        .param("a", accountId)
        .update();
  }

  private long stageFile(long sessionId, String moneyAccountName) {
    return jdbcClient
        .sql(
            """
            insert into import_file
              (import_session_id, filename, money_account_name, charset, date_order)
            values (:s, 'export.qif', :n, 'utf_8', 'day_month')
            returning import_file_id
            """)
        .param("s", sessionId)
        .param("n", moneyAccountName)
        .query(Long.class)
        .single();
  }

  private long stageTransaction(long fileId, LocalDate date) {
    return jdbcClient
        .sql(
            "insert into import_transaction (import_file_id, date, payee_text)"
                + " values (:f, :d, 'Grocer') returning import_transaction_id")
        .param("f", fileId)
        .param("d", date)
        .query(Long.class)
        .single();
  }

  private void fundingLeg(long transactionId, String moneyAccountName, String amount) {
    jdbcClient
        .sql(
            "insert into import_posting"
                + " (import_transaction_id, amount, money_account_name, funding)"
                + " values (:t, :a, :n, true)")
        .param("t", transactionId)
        .param("a", new BigDecimal(amount))
        .param("n", moneyAccountName)
        .update();
  }

  private void transferLeg(
      long transactionId, String moneyAccountName, String amount, String counterAmount) {
    jdbcClient
        .sql(
            "insert into import_posting"
                + " (import_transaction_id, amount, money_account_name, funding, counter_amount)"
                + " values (:t, :a, :n, false, :c)")
        .param("t", transactionId)
        .param("a", new BigDecimal(amount))
        .param("n", moneyAccountName)
        .param("c", counterAmount == null ? null : new BigDecimal(counterAmount))
        .update();
  }

  private void categoryLeg(long transactionId, String moneyCategoryPath, String amount) {
    jdbcClient
        .sql(
            "insert into import_posting"
                + " (import_transaction_id, amount, money_category_path, funding)"
                + " values (:t, :a, :p, false)")
        .param("t", transactionId)
        .param("a", new BigDecimal(amount))
        .param("p", moneyCategoryPath)
        .update();
  }

  private long ledgerTransaction(String date) {
    return jdbcClient
        .sql("insert into transaction (date) values (:d) returning transaction_id")
        .param("d", LocalDate.parse(date))
        .query(Long.class)
        .single();
  }

  private void posting(long transactionId, long accountId, String amount) {
    jdbcClient
        .sql("insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)")
        .param("t", transactionId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .update();
  }

  private void posting(long transactionId, long accountId, String amount, String baseAmount) {
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, base_amount)"
                + " values (:t, :a, :amt, :base)")
        .param("t", transactionId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .param("base", new BigDecimal(baseAmount))
        .update();
  }
}
