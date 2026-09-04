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
 * within staging (import.md §6.1; plan e1) <strong>and</strong> cross-currency parking (§6.2/§6.5;
 * plan e2a). The logic lives in the SQL: a self-join over {@code import_posting} → {@code
 * import_transaction} → {@code import_file} → {@code import_account} on both sides, a window
 * function pairing identical same-day transfers 1:1, and a filtered aggregate that tells a simple
 * transfer (excludable whole) from a split (only its transfer leg is a mirror).
 *
 * <p>The cross-currency cases exercise the second matching rule: a transfer whose two mapped
 * account currencies differ carries no far-side amount in QIF, so it <em>parks</em> until the
 * mirror supplies the real far amount. The parked-then-resolved pair is matched on the
 * <strong>loosened</strong> signature — date + the two mapped account ids crossing, near-side
 * amount ignored — and only when that directed shape is unambiguous 1:1; anything ambiguous stays
 * parked for a manual match (e2b).
 *
 * <p>Crafted staging rows via raw {@link JdbcClient}; the query under test is the real repository.
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
    return account(name, "EUR");
  }

  /** An asset account in {@code currencyCode} (EUR and CHF are both seeded by V2). */
  private long account(String name, String currencyCode) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, 'asset', :c)"
                + " returning account_id")
        .param("n", name)
        .param("c", currencyCode)
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

  private BigDecimal counterAmountOf(long postingId) {
    return jdbcClient
        .sql("select counter_amount from import_posting where import_posting_id = :id")
        .param("id", postingId)
        .query(BigDecimal.class)
        .optional()
        .orElse(null);
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
  void differentAmountsInOneCurrencyNeverMatch() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A"));
    long fileB = stageFile(session, "B", account("B"));
    // Both accounts EUR, so this is not a cross-currency park — the e1 rule applies, and it only
    // pairs equal-and-opposite legs. Unequal legs in one currency are two unrelated transfers.
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

  // ── cross-currency parking (import.md §6.2/§6.5; plan e2a) ──────────────────

  @Test
  void loneCrossCurrencyTransferParksUntilItsMirrorArrives() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"));

    // Only the EUR side is staged so far: €100 out of Euro, into the CHF account.
    long euroLeg = stageTransfer(euroFile, LocalDate.of(2015, 5, 5), "Euro", "Franc", "-100.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("parked");
    assertThat(counterAmountOf(euroLeg)).isNull();
  }

  @Test
  void unambiguousCrossCurrencyPairResolvesAndCarriesEachFarFundingAmount() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));

    // €100 leaves Euro; CHF 150 arrives in Franc — the far amount the EUR file cannot state.
    long euroLeg = stageTransfer(euroFile, LocalDate.of(2016, 6, 6), "Euro", "Franc", "-100.00");
    long francLeg = stageTransfer(francFile, LocalDate.of(2016, 6, 6), "Franc", "Euro", "150.00");

    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);

    // Earlier-staged sighting survives and books; the later one is the skippable mirror.
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(francLeg))).isEqualTo("mirrored");
    assertThat(mirrorPairOf(euroLeg)).isEqualTo(francLeg);
    assertThat(mirrorPairOf(francLeg)).isEqualTo(euroLeg);
    // Each transfer leg carries the *other* file's funding-leg amount, sign preserved: the EUR
    // leg's counterpart is CHF +150 (Franc's funding leg), the CHF leg's is EUR −100.
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");
    assertThat(counterAmountOf(francLeg)).isEqualByComparingTo("-100.00");
  }

  @Test
  void ambiguousSameDayCrossCurrencyTransfersAllStayParkedForManualMatch() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));

    // Two Euro→Franc and two Franc→Euro on one day: the loosened signature (date + crossing ids)
    // cannot tell which pairs with which without a rate — e2a leaves them all parked for e2b.
    long euroA = stageTransfer(euroFile, LocalDate.of(2017, 7, 7), "Euro", "Franc", "-100.00");
    long euroB = stageTransfer(euroFile, LocalDate.of(2017, 7, 7), "Euro", "Franc", "-200.00");
    long francA = stageTransfer(francFile, LocalDate.of(2017, 7, 7), "Franc", "Euro", "150.00");
    long francB = stageTransfer(francFile, LocalDate.of(2017, 7, 7), "Franc", "Euro", "305.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(
            List.of(
                stateOf(txnOf(euroA)),
                stateOf(txnOf(euroB)),
                stateOf(txnOf(francA)),
                stateOf(txnOf(francB))))
        .containsOnly("parked");
    assertThat(mirrorPairOf(euroA)).isNull();
    assertThat(counterAmountOf(francA)).isNull();
  }

  @Test
  void oppositeDirectionCrossCurrencyTransfersWithBothMirrorsAbsentDoNotFalselyPair() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));

    // A genuine EUR→CHF transfer whose CHF-side mirror is not staged, and an unrelated CHF→EUR
    // transfer whose EUR-side mirror is not staged — same day, same two accounts. Both directed
    // shapes are 1:1, but the two near legs are same-signed (each is money *into* the named
    // account), so they are not the two halves of one transfer — the sign guard keeps them parked
    // until their real mirrors arrive.
    long outbound = stageTransfer(euroFile, LocalDate.of(2022, 3, 3), "Euro", "Franc", "-100.00");
    long inbound = stageTransfer(francFile, LocalDate.of(2022, 3, 3), "Franc", "Euro", "-80.00");

    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(outbound))).isEqualTo("parked");
    assertThat(stateOf(txnOf(inbound))).isEqualTo("parked");
    assertThat(mirrorPairOf(outbound)).isNull();
    assertThat(counterAmountOf(outbound)).isNull();
  }

  @Test
  void parksOnlyOnceBothSidesAreMappedToKnownCurrencies() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", null); // counterparty not mapped yet

    long euroLeg = stageTransfer(euroFile, LocalDate.of(2018, 8, 8), "Euro", "Franc", "-100.00");
    stageTransfer(francFile, LocalDate.of(2018, 8, 8), "Franc", "Euro", "150.00");

    // Franc's currency is unknown, so cross-currency cannot be established — nothing parks.
    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");

    mapAccount(session, "Franc", account("Sparen", "CHF"));
    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");
  }

  @Test
  void remappingCounterpartyToSameCurrencyUnparksAndClearsCounterAmount() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    long euroLeg = stageTransfer(euroFile, LocalDate.of(2019, 9, 9), "Euro", "Franc", "-100.00");
    final long francLeg =
        stageTransfer(francFile, LocalDate.of(2019, 9, 9), "Franc", "Euro", "150.00");

    importMirrorRepository.rematch(session);
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");

    // Point "Franc" at a EUR account instead: the two legs no longer cross a currency boundary, so
    // the park and its counter amount are cleared on the next run. The legs are €100 vs €150, so
    // the e1 rule does not pair them either — both end up plain, unmatched.
    mapAccount(session, "Franc", account("Franc EUR", "EUR"));
    assertThat(importMirrorRepository.rematch(session)).isZero();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(francLeg))).isEqualTo("ready");
    assertThat(counterAmountOf(euroLeg)).isNull();
    assertThat(counterAmountOf(francLeg)).isNull();
  }

  @Test
  void rerunKeepsCrossCurrencyResolutionStable() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    long euroLeg = stageTransfer(euroFile, LocalDate.of(2020, 1, 1), "Euro", "Franc", "-100.00");
    final long francLeg =
        stageTransfer(francFile, LocalDate.of(2020, 1, 1), "Franc", "Euro", "150.00");

    importMirrorRepository.rematch(session);
    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);

    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(francLeg))).isEqualTo("mirrored");
    assertThat(mirrorPairOf(euroLeg)).isEqualTo(francLeg);
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");
  }

  @Test
  void sameCurrencyTransferIsNeverParked() {
    long session = openSession();
    long fileA = stageFile(session, "A", account("A", "EUR"));
    long fileB = stageFile(session, "B", account("B", "EUR"));
    long legA = stageTransfer(fileA, LocalDate.of(2021, 2, 2), "A", "B", "-40.00");
    long legB = stageTransfer(fileB, LocalDate.of(2021, 2, 2), "B", "A", "40.00");

    // Same currency: the e1 rule owns this — an equal-and-opposite pair still mirrors, never parks.
    assertThat(importMirrorRepository.rematch(session)).isEqualTo(1);
    assertThat(stateOf(txnOf(legA))).isEqualTo("ready");
    assertThat(stateOf(txnOf(legB))).isEqualTo("mirrored");
    assertThat(counterAmountOf(legA)).isNull();
  }
}
