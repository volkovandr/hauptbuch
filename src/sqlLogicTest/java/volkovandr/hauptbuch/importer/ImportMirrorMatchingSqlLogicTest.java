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
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ImportMirrorRepository#rematch} — transfer mirror matching
 * within staging (import.md §6.1; plan e1). The logic lives in the SQL: a self-join over {@code
 * import_posting} → {@code import_transaction} → {@code import_file} → {@code import_account} on
 * both sides, a window function pairing identical same-day transfers 1:1, and a filtered aggregate
 * that tells a simple transfer (excludable whole) from a split (only its transfer leg is a mirror).
 * Crafted staging rows via raw {@link JdbcClient}; the query under test is the real repository.
 * {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportMirrorMatchingSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired ImportMirrorRepository importMirrorRepository;

  // ── crafted-staging helpers ────────────────────────────────────────────────

  private long openSession() {
    return insertSession("open");
  }

  private long insertSession(String state) {
    return jdbcClient
        .sql("insert into import_session (state) values (:s) returning import_session_id")
        .param("s", state)
        .query(Long.class)
        .single();
  }

  private long account(String name) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, 'asset', 'EUR')"
                + " returning account_id")
        .param("n", name)
        .query(Long.class)
        .single();
  }

  /** Stage a file for a Money account, mapping that name to {@code accountId} (null = unmapped). */
  private long stageFile(long sessionId, String moneyAccountName, Long accountId) {
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

  private void mapAccount(long sessionId, String moneyAccountName, Long accountId) {
    jdbcClient
        .sql(
            "update import_account set account_id = :a"
                + " where import_session_id = :s and money_account_name = :n")
        .param("a", accountId)
        .param("s", sessionId)
        .param("n", moneyAccountName)
        .update();
  }

  private long stageTransaction(long fileId, LocalDate date) {
    return jdbcClient
        .sql(
            "insert into import_transaction (import_file_id, date) values (:f, :d)"
                + " returning import_transaction_id")
        .param("f", fileId)
        .param("d", date)
        .query(Long.class)
        .single();
  }

  private long leg(
      long transactionId, String amount, String categoryPath, String accountName, boolean funding) {
    return jdbcClient
        .sql(
            """
            insert into import_posting
              (import_transaction_id, amount, money_category_path, money_account_name, funding)
            values (:t, :a, :p, :acc, :fund)
            returning import_posting_id
            """)
        .param("t", transactionId)
        .param("a", new BigDecimal(amount))
        .param("p", categoryPath)
        .param("acc", accountName)
        .param("fund", funding)
        .query(Long.class)
        .single();
  }

  /**
   * A one-line transfer: the funding leg on {@code fromAccount} carrying {@code fundingAmount}, and
   * one transfer leg naming {@code toAccount} carrying its negation — Hauptbuch's sign convention
   * ({@link ImportPosting}). Returns the transfer leg's posting id.
   */
  private long stageTransfer(
      long fileId, LocalDate date, String fromAccount, String toAccount, String fundingAmount) {
    long id = stageTransaction(fileId, date);
    long transferLeg =
        leg(id, new BigDecimal(fundingAmount).negate().toPlainString(), null, toAccount, false);
    leg(id, fundingAmount, null, fromAccount, true);
    return transferLeg;
  }

  private String stateOf(long transactionId) {
    return jdbcClient
        .sql("select state from import_transaction where import_transaction_id = :id")
        .param("id", transactionId)
        .query(String.class)
        .single();
  }

  private Long mirrorPairOf(long postingId) {
    return jdbcClient
        .sql("select mirror_pair_id from import_posting where import_posting_id = :id")
        .param("id", postingId)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  private long txnOf(long postingId) {
    return jdbcClient
        .sql("select import_transaction_id from import_posting where import_posting_id = :id")
        .param("id", postingId)
        .query(Long.class)
        .single();
  }

  // ── tests ──────────────────────────────────────────────────────────────────

  @Test
  void simpleTransferMirrorMarksTheLaterSightingAndLinksThePair() {
    long session = openSession();
    long currentFile = stageFile(session, "Current", account("Giro"));
    long savingsFile = stageFile(session, "Savings", account("Sparbuch"));

    // €100 Current → Savings, seen from Current's file first, then mirrored in Savings' file.
    long currentLeg =
        stageTransfer(currentFile, LocalDate.of(2005, 1, 1), "Current", "Savings", "-100.00");
    long savingsLeg =
        stageTransfer(savingsFile, LocalDate.of(2005, 1, 1), "Savings", "Current", "100.00");

    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);

    assertThat(stateOf(txnOf(currentLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(savingsLeg))).isEqualTo("mirrored");
    assertThat(mirrorPairOf(currentLeg)).isEqualTo(savingsLeg);
    assertThat(mirrorPairOf(savingsLeg)).isEqualTo(currentLeg);
  }

  @Test
  void splitLegMirrorMatchesAndExcludesTheUnsplitSighting() {
    long session = openSession();
    long currentFile = stageFile(session, "Current", account("Giro"));
    long maxFile = stageFile(session, "Max", account("Max EUR"));

    // Current's file: a split — €60 Food, €40 lent to Max — funded €100 from Current.
    long splitTxn = stageTransaction(currentFile, LocalDate.of(2005, 3, 2));
    leg(splitTxn, "60.00", "Food", null, false);
    final long splitTransferLeg = leg(splitTxn, "40.00", null, "Max", false);
    leg(splitTxn, "-100.00", null, "Current", true);

    // Max's file: the same €40 as an ordinary unsplit transfer back to Current.
    long unsplitLeg = stageTransfer(maxFile, LocalDate.of(2005, 3, 2), "Max", "Current", "40.00");

    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);

    assertThat(stateOf(splitTxn)).isEqualTo("ready");
    assertThat(stateOf(txnOf(unsplitLeg))).isEqualTo("mirrored");
    assertThat(mirrorPairOf(splitTransferLeg)).isEqualTo(unsplitLeg);
    assertThat(mirrorPairOf(unsplitLeg)).isEqualTo(splitTransferLeg);
  }

  @Test
  void rerunIsIdempotent() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));
    long legA = stageTransfer(fileA, LocalDate.of(2006, 6, 6), "A", "B", "-25.00");
    final long legB = stageTransfer(fileB, LocalDate.of(2006, 6, 6), "B", "A", "25.00");

    importMirrorRepository.rematch(session);
    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);

    assertThat(stateOf(txnOf(legA))).isEqualTo("ready");
    assertThat(stateOf(txnOf(legB))).isEqualTo("mirrored");
    assertThat(mirrorPairOf(legA)).isEqualTo(legB);
  }

  @Test
  void matchesOnlyAfterBothAccountsAreMappedAndClearsAgainWhenTheMapChanges() {
    long session = openSession();
    long a = account("A");
    long b = account("B");
    long fileA = stageFile(session, "A", a);
    long fileB = stageFile(session, "B", null); // B not yet mapped
    final long legA = stageTransfer(fileA, LocalDate.of(2007, 7, 7), "A", "B", "-10.00");
    final long legB = stageTransfer(fileB, LocalDate.of(2007, 7, 7), "B", "A", "10.00");

    // Unmapped counterparty ⇒ no match yet.
    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(legB))).isEqualTo("ready");

    mapAccount(session, "B", b);
    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);
    assertThat(stateOf(txnOf(legB))).isEqualTo("mirrored");

    // Point "B" at the same Hauptbuch account as "A": the two legs no longer cross, so the stale
    // mark and link are cleared on the next run.
    mapAccount(session, "B", a);
    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(legB))).isEqualTo("ready");
    assertThat(mirrorPairOf(legA)).isNull();
    assertThat(mirrorPairOf(legB)).isNull();
  }

  @Test
  void differentAmountsNeverMatch() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));
    // A cross-currency transfer: the far side's native amount differs — parked, not matched (slice
    // e2). e1 only pairs equal-and-opposite legs.
    long legA = stageTransfer(fileA, LocalDate.of(2008, 8, 8), "A", "B", "-100.00");
    long legB = stageTransfer(fileB, LocalDate.of(2008, 8, 8), "B", "A", "90.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(legA))).isEqualTo("ready");
    assertThat(stateOf(txnOf(legB))).isEqualTo("ready");
  }

  @Test
  void differentDatesNeverMatch() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));
    stageTransfer(fileA, LocalDate.of(2009, 1, 1), "A", "B", "-50.00");
    long legB = stageTransfer(fileB, LocalDate.of(2009, 1, 2), "B", "A", "50.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(legB))).isEqualTo("ready");
  }

  @Test
  void twoIdenticalTransfersOnOneDayPairOneToOne() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));
    long a1 = stageTransfer(fileA, LocalDate.of(2010, 5, 5), "A", "B", "-50.00");
    long a2 = stageTransfer(fileA, LocalDate.of(2010, 5, 5), "A", "B", "-50.00");
    long b1 = stageTransfer(fileB, LocalDate.of(2010, 5, 5), "B", "A", "50.00");
    long b2 = stageTransfer(fileB, LocalDate.of(2010, 5, 5), "B", "A", "50.00");

    // Both €50 transfers match — 2 kept in A's file, 2 excluded in B's — never one swallowing
    // three.
    assertThat(importMirrorRepository.rematch(session)).isEqualTo(2);
    assertThat(List.of(stateOf(txnOf(a1)), stateOf(txnOf(a2)))).containsOnly("ready");
    assertThat(List.of(stateOf(txnOf(b1)), stateOf(txnOf(b2)))).containsOnly("mirrored");
    assertThat(mirrorPairOf(a1)).isIn(b1, b2);
    assertThat(mirrorPairOf(a2)).isIn(b1, b2);
    assertThat(mirrorPairOf(a1)).isNotEqualTo(mirrorPairOf(a2));
  }

  @Test
  void oppositeDirectionTransfersOnOneDayEachMatchTheirOwnMirror() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));
    // A → B €40 and B → A €40 on the same day: two distinct transfers, four legs, one |amount|.
    final long abSourceLeg = stageTransfer(fileA, LocalDate.of(2011, 2, 2), "A", "B", "-40.00");
    final long abMirrorLeg = stageTransfer(fileB, LocalDate.of(2011, 2, 2), "B", "A", "40.00");
    final long baSourceLeg = stageTransfer(fileB, LocalDate.of(2011, 2, 2), "B", "A", "-40.00");
    final long baMirrorLeg = stageTransfer(fileA, LocalDate.of(2011, 2, 2), "A", "B", "40.00");

    assertThat(importMirrorRepository.rematch(session)).isEqualTo(2);

    // Each transfer pairs with its own mirror — never A→B's near leg with B→A's near leg.
    assertThat(mirrorPairOf(abSourceLeg)).isEqualTo(abMirrorLeg);
    assertThat(mirrorPairOf(baSourceLeg)).isEqualTo(baMirrorLeg);
    // One sighting of each transfer is kept, the other excluded.
    assertThat(stateOf(txnOf(abSourceLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(abMirrorLeg))).isEqualTo("mirrored");
    assertThat(stateOf(txnOf(baSourceLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(baMirrorLeg))).isEqualTo("mirrored");
  }

  @Test
  void categoryLegsAreNeverMirrorCandidates() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));

    long spendTxn = stageTransaction(fileA, LocalDate.of(2012, 3, 3));
    leg(spendTxn, "30.00", "Food", null, false);
    leg(spendTxn, "-30.00", null, "A", true);

    long transferLeg = stageTransfer(fileB, LocalDate.of(2012, 3, 3), "B", "A", "30.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(spendTxn)).isEqualTo("ready");
    assertThat(stateOf(txnOf(transferLeg))).isEqualTo("ready");
  }

  @Test
  void bothSightingsSplitLinksNothing() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));

    final long splitA = stageTransaction(fileA, LocalDate.of(2013, 4, 4));
    leg(splitA, "70.00", "Food", null, false);
    final long transferLegInA = leg(splitA, "30.00", null, "B", false);
    leg(splitA, "-100.00", null, "A", true);

    final long splitB = stageTransaction(fileB, LocalDate.of(2013, 4, 4));
    leg(splitB, "70.00", "Fuel", null, false);
    final long transferLegInB = leg(splitB, "-30.00", null, "A", false);
    leg(splitB, "-40.00", null, "B", true);

    // Neither transaction can be excluded wholesale — e1 leaves both booking; e4's issues list
    // surfaces the residual.
    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(splitA)).isEqualTo("ready");
    assertThat(stateOf(splitB)).isEqualTo("ready");
    assertThat(mirrorPairOf(transferLegInA)).isNull();
    assertThat(mirrorPairOf(transferLegInB)).isNull();
  }

  @Test
  void scopesToTheGivenSession() {
    long session = openSession();
    long other = insertSession("committed");
    long fileA = stageFile(other, "A", account("A"));
    long fileB = stageFile(other, "B", account("B"));
    long legB = stageTransfer(fileB, LocalDate.of(2014, 1, 1), "B", "A", "15.00");
    stageTransfer(fileA, LocalDate.of(2014, 1, 1), "A", "B", "-15.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(legB))).isEqualTo("ready");
  }
}
