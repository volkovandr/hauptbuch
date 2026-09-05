package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ImportMirrorRepository#parkedCrossCurrencyLegs}, {@link
 * ImportMirrorRepository#manualMatch}, {@link ImportMirrorRepository#closeParkWithFarAmount},
 * {@link ImportMirrorRepository#clearCounterAmountOfMirrorsIn} — the e2b resolutions for a
 * cross-currency park automatic matching (e2a) could not resolve on its own (import.md §6.4/§6.5) —
 * and {@link ImportMirrorRepository#resolvedCrossCurrencyRateCandidates}, the plan-e3 rate
 * write-back's raw-facts input (§6.3).
 *
 * <p>Crafted staging rows via raw {@link JdbcClient}; the queries under test are the real
 * repository methods. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportCrossCurrencyParkSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired ImportMirrorRepository importMirrorRepository;

  // ── crafted-staging helpers (ImportMirrorMatchingSqlLogicTest precedent) ───────────────────

  private long openSession() {
    return jdbcClient
        .sql("insert into import_session (state) values ('open') returning import_session_id")
        .query(Long.class)
        .single();
  }

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

  private long stageFile(long sessionId, String moneyAccountName, long accountId) {
    return stageFile(sessionId, moneyAccountName, accountId, true);
  }

  private long stageFile(
      long sessionId, String moneyAccountName, long accountId, boolean expectFile) {
    jdbcClient
        .sql(
            """
            insert into import_account
              (import_session_id, money_account_name, account_id, expect_file)
            values (:s, :n, :a, :e)
            on conflict (import_session_id, money_account_name)
              do update set account_id = excluded.account_id, expect_file = excluded.expect_file
            """)
        .param("s", sessionId)
        .param("n", moneyAccountName)
        .param("a", accountId)
        .param("e", expectFile)
        .update();
    return jdbcClient
        .sql(
            """
            insert into import_file
              (import_session_id, filename, money_account_name, charset, date_order)
            values (:s, :n || '.qif', :n, 'utf_8', 'day_month')
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
            "insert into import_transaction (import_file_id, date, state) values (:f, :d, 'parked')"
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
   * A one-line parked transfer: the funding leg on {@code fromAccount} carrying {@code
   * fundingAmount}, and one transfer leg naming {@code toAccount} carrying its negation
   * (Hauptbuch's sign convention). Returns the transfer leg's posting id.
   */
  private long stageParkedTransfer(
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

  private long txnOf(long postingId) {
    return jdbcClient
        .sql("select import_transaction_id from import_posting where import_posting_id = :id")
        .param("id", postingId)
        .query(Long.class)
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

  private BigDecimal counterAmountOf(long postingId) {
    return jdbcClient
        .sql("select counter_amount from import_posting where import_posting_id = :id")
        .param("id", postingId)
        .query(BigDecimal.class)
        .optional()
        .orElse(null);
  }

  // ── parkedCrossCurrencyLegs ────────────────────────────────────────────────

  @Test
  void listsEveryStillParkedCrossCurrencyLegWithTheFarAccountsExpectFile() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), false);
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2016, 6, 6), "Euro", "Franc", "-100.00");

    List<ImportCrossCurrencyPark> parks = importMirrorRepository.parkedCrossCurrencyLegs(session);

    assertThat(parks)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.importPostingId()).isEqualTo(euroLeg);
              assertThat(row.importTransactionId()).isEqualTo(txnOf(euroLeg));
              assertThat(row.date()).isEqualTo(LocalDate.of(2016, 6, 6));
              assertThat(row.nearMoneyAccountName()).isEqualTo("Euro");
              assertThat(row.farMoneyAccountName()).isEqualTo("Franc");
              assertThat(row.amount()).isEqualByComparingTo("100.00");
              assertThat(row.farExpectFile()).isFalse();
            });
  }

  @Test
  void excludesReadySameCurrencyLegs() {
    long session = openSession();
    // "Euro" only needs to be mapped, not to have its own staged file, for the transfer leg below.
    stageFile(session, "Euro", account("Giro", "EUR"));
    // A same-currency transfer, staged 'ready' — never a cross-currency park.
    long sameCurrencyFile = stageFile(session, "Other", account("Other", "EUR"));
    long readyTxn = stageTransaction(sameCurrencyFile, LocalDate.of(2016, 1, 1));
    leg(readyTxn, "10.00", null, "Euro", false);
    leg(readyTxn, "-10.00", null, "Other", true);
    jdbcClient
        .sql("update import_transaction set state = 'ready' where import_transaction_id = :id")
        .param("id", readyTxn)
        .update();

    assertThat(importMirrorRepository.parkedCrossCurrencyLegs(session)).isEmpty();
  }

  @Test
  void excludesAnAlreadyResolvedLeg() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), false);
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2024, 9, 9), "Euro", "Franc", "-100.00");

    importMirrorRepository.closeParkWithFarAmount(session, euroLeg, new BigDecimal("150.00"));

    // The leg now books (state 'ready', counter_amount set) — no longer a review-panel candidate.
    assertThat(importMirrorRepository.parkedCrossCurrencyLegs(session)).isEmpty();
  }

  // ── manualMatch ────────────────────────────────────────────────────────────

  @Test
  void manualMatchResolvesTwoSimpleTransfersSymmetrically() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2016, 6, 6), "Euro", "Franc", "-100.00");
    long francLeg =
        stageParkedTransfer(francFile, LocalDate.of(2016, 6, 6), "Franc", "Euro", "150.00");

    assertThat(importMirrorRepository.manualMatch(session, euroLeg, francLeg)).isTrue();

    assertThat(mirrorPairOf(euroLeg)).isEqualTo(francLeg);
    assertThat(mirrorPairOf(francLeg)).isEqualTo(euroLeg);
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");
    assertThat(counterAmountOf(francLeg)).isEqualByComparingTo("-100.00");
    // The earlier-id sighting (euroLeg's transaction) survives; the later is the skippable mirror.
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
    assertThat(stateOf(txnOf(francLeg))).isEqualTo("mirrored");
  }

  @Test
  void manualMatchKeepsSplitsOtherLegsBookingAndExcludesTheSimpleSighting() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    // Euro's file: a split — €60 Food, €40 transferred to Franc — funded €100.
    long splitTxn = stageTransaction(euroFile, LocalDate.of(2017, 2, 2));
    leg(splitTxn, "60.00", "Food", null, false);
    long splitTransferLeg = leg(splitTxn, "40.00", null, "Franc", false);
    leg(splitTxn, "-100.00", null, "Euro", true);
    long francLeg =
        stageParkedTransfer(francFile, LocalDate.of(2017, 2, 2), "Franc", "Euro", "60.00");

    assertThat(importMirrorRepository.manualMatch(session, splitTransferLeg, francLeg)).isTrue();

    assertThat(stateOf(splitTxn)).isEqualTo("ready");
    assertThat(stateOf(txnOf(francLeg))).isEqualTo("mirrored");
    assertThat(mirrorPairOf(splitTransferLeg)).isEqualTo(francLeg);
    assertThat(counterAmountOf(splitTransferLeg)).isEqualByComparingTo("60.00");
    // francLeg's counter_amount is splitTransferLeg's OWN amount, negated (-40.00) — the portion of
    // the split that actually crossed to Franc — never the split's whole funding total (-100.00),
    // which also covers the unrelated €60 Food leg.
    assertThat(counterAmountOf(francLeg)).isEqualByComparingTo("-40.00");
  }

  @Test
  void manualMatchRefusesTwoLegsMovingInTheSameDirection() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    // Both legs read as money moving INTO the far account (positive) — a real mirror's two legs are
    // always opposite-signed (one side's money-in is the other side's money-out), so this pair
    // cannot be one real transfer's two sightings, however tempting the account-crossing looks.
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2020, 6, 6), "Euro", "Franc", "-100.00");
    long francLeg =
        stageParkedTransfer(francFile, LocalDate.of(2020, 6, 6), "Franc", "Euro", "-150.00");

    assertThat(importMirrorRepository.manualMatch(session, euroLeg, francLeg)).isFalse();
    assertThat(mirrorPairOf(euroLeg)).isNull();
    assertThat(counterAmountOf(euroLeg)).isNull();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("parked");
  }

  @Test
  void manualMatchRefusesZeroAmountLeg() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    // sign(0) = 0, which must never read as "opposite" to a real leg's sign — a zero-amount leg
    // (e.g. a degenerate €0.00 split line) is never a valid mirror match, however the accounts
    // cross.
    long zeroTxn = stageTransaction(euroFile, LocalDate.of(2020, 7, 7));
    long zeroLeg = leg(zeroTxn, "0.00", null, "Franc", false);
    leg(zeroTxn, "0.00", null, "Euro", true);
    long francLeg =
        stageParkedTransfer(francFile, LocalDate.of(2020, 7, 7), "Franc", "Euro", "100.00");

    assertThat(importMirrorRepository.manualMatch(session, zeroLeg, francLeg)).isFalse();
    assertThat(mirrorPairOf(zeroLeg)).isNull();
    assertThat(counterAmountOf(zeroLeg)).isNull();
  }

  @Test
  void manualMatchRefusesPairThatDoesNotCrossTheSameTwoAccounts() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    // "Franc" must still be mapped for euroLeg's own crossing to resolve, even though no file of
    // its own is staged here — the point under test is that its mirror candidate is unrelated.
    stageFile(session, "Franc", account("Sparen", "CHF"));
    long otherFrancFile = stageFile(session, "OtherFranc", account("Other Sparen", "CHF"));
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2018, 3, 3), "Euro", "Franc", "-100.00");
    // A different CHF account's transfer — not the mirror of euroLeg at all.
    long unrelatedLeg =
        stageParkedTransfer(
            otherFrancFile, LocalDate.of(2018, 3, 3), "OtherFranc", "Euro", "60.00");

    assertThat(importMirrorRepository.manualMatch(session, euroLeg, unrelatedLeg)).isFalse();
    assertThat(mirrorPairOf(euroLeg)).isNull();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("parked");
  }

  @Test
  void manualMatchRefusesWhenBothSidesAreSplits() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    long splitA = stageTransaction(euroFile, LocalDate.of(2019, 4, 4));
    leg(splitA, "70.00", "Food", null, false);
    final long transferLegA = leg(splitA, "30.00", null, "Franc", false);
    leg(splitA, "-100.00", null, "Euro", true);
    long splitB = stageTransaction(francFile, LocalDate.of(2019, 4, 4));
    leg(splitB, "20.00", "Fuel", null, false);
    long transferLegB = leg(splitB, "-18.00", null, "Euro", false);
    leg(splitB, "-2.00", null, "Franc", true);

    assertThat(importMirrorRepository.manualMatch(session, transferLegA, transferLegB)).isFalse();
    assertThat(mirrorPairOf(transferLegA)).isNull();
    assertThat(mirrorPairOf(transferLegB)).isNull();
  }

  @Test
  void manualMatchRefusesTheSamePostingTwice() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"));
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2020, 5, 5), "Euro", "Franc", "-100.00");

    assertThat(importMirrorRepository.manualMatch(session, euroLeg, euroLeg)).isFalse();
  }

  // ── closeParkWithFarAmount ─────────────────────────────────────────────────

  @Test
  void closeParkWithFarAmountSetsCounterAmountAndReadiesWithNoMirror() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), false); // expect-file cleared
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2021, 6, 6), "Euro", "Franc", "-100.00");

    boolean closed =
        importMirrorRepository.closeParkWithFarAmount(session, euroLeg, new BigDecimal("150.00"));

    assertThat(closed).isTrue();
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");
    assertThat(mirrorPairOf(euroLeg)).isNull();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("ready");
  }

  @Test
  void closeParkWithFarAmountRefusesWhileTheFarAccountStillExpectsFile() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), true); // still expecting a file
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2022, 7, 7), "Euro", "Franc", "-100.00");

    boolean closed =
        importMirrorRepository.closeParkWithFarAmount(session, euroLeg, new BigDecimal("150.00"));

    assertThat(closed).isFalse();
    assertThat(counterAmountOf(euroLeg)).isNull();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("parked");
  }

  @Test
  void closeParkWithFarAmountRefusesZero() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), false);
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2023, 2, 2), "Euro", "Franc", "-100.00");

    boolean closed =
        importMirrorRepository.closeParkWithFarAmount(session, euroLeg, BigDecimal.ZERO);

    assertThat(closed).isFalse();
    assertThat(counterAmountOf(euroLeg)).isNull();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("parked");
  }

  @Test
  void closeParkWithFarAmountRefusesAnAmountWithTheWrongSign() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), false);
    // The transfer leg's own amount is +100.00 (Hauptbuch sign convention) — a far amount must
    // share that sign; a negative figure would post the transfer backwards.
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2023, 3, 3), "Euro", "Franc", "-100.00");

    boolean closed =
        importMirrorRepository.closeParkWithFarAmount(session, euroLeg, new BigDecimal("-150.00"));

    assertThat(closed).isFalse();
    assertThat(counterAmountOf(euroLeg)).isNull();
    assertThat(stateOf(txnOf(euroLeg))).isEqualTo("parked");
  }

  // ── resolvedCrossCurrencyRateCandidates (plan e3, §6.3) ───────────────────────

  @Test
  void resolvedCrossCurrencyRateCandidatesReportsBothSightingsOfAnAutomaticallyResolvedPair() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    LocalDate date = LocalDate.of(2026, 5, 5);
    long euroLeg = stageParkedTransfer(euroFile, date, "Euro", "Franc", "-100.00");
    long francLeg = stageParkedTransfer(francFile, date, "Franc", "Euro", "150.00");
    importMirrorRepository.manualMatch(session, euroLeg, francLeg);

    List<ImportCrossCurrencyRateCandidate> candidates =
        importMirrorRepository.resolvedCrossCurrencyRateCandidates(session);

    // Symmetric: the resolved pair yields a candidate from each of its two sightings, both
    // carrying the same real EUR/CHF amounts (just the currency/amount order flipped).
    assertThat(candidates).hasSize(2);
    assertThat(candidates)
        .allSatisfy(
            c -> {
              assertThat(c.date()).isEqualTo(date);
              assertThat(Set.of(c.currencyA(), c.currencyB())).isEqualTo(Set.of("EUR", "CHF"));
            });
  }

  @Test
  void resolvedCrossCurrencyRateCandidatesReportsHandEnteredResolution() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"), false);
    LocalDate date = LocalDate.of(2026, 6, 6);
    long euroLeg = stageParkedTransfer(euroFile, date, "Euro", "Franc", "-100.00");
    importMirrorRepository.closeParkWithFarAmount(session, euroLeg, new BigDecimal("150.00"));

    List<ImportCrossCurrencyRateCandidate> candidates =
        importMirrorRepository.resolvedCrossCurrencyRateCandidates(session);

    assertThat(candidates)
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.date()).isEqualTo(date);
              assertThat(c.currencyA()).isEqualTo("EUR");
              assertThat(c.amountA()).isEqualByComparingTo("100.00");
              assertThat(c.currencyB()).isEqualTo("CHF");
              assertThat(c.amountB()).isEqualByComparingTo("150.00");
            });
  }

  @Test
  void resolvedCrossCurrencyRateCandidatesExcludesStillParkedLegs() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    stageFile(session, "Franc", account("Sparen", "CHF"));
    stageParkedTransfer(euroFile, LocalDate.of(2026, 7, 7), "Euro", "Franc", "-100.00");

    assertThat(importMirrorRepository.resolvedCrossCurrencyRateCandidates(session)).isEmpty();
  }

  // ── clearCounterAmountOfMirrorsIn (file-removal orphan clean-up, §6.4 vs. a stale link) ──────

  @Test
  void clearCounterAmountOfMirrorsInClearsTheSurvivorWhenItsPartnerFileIsRemoved() {
    long session = openSession();
    long euroFile = stageFile(session, "Euro", account("Giro", "EUR"));
    long francFile = stageFile(session, "Franc", account("Sparen", "CHF"));
    long euroLeg =
        stageParkedTransfer(euroFile, LocalDate.of(2023, 8, 8), "Euro", "Franc", "-100.00");
    long francLeg =
        stageParkedTransfer(francFile, LocalDate.of(2023, 8, 8), "Franc", "Euro", "150.00");
    importMirrorRepository.manualMatch(session, euroLeg, francLeg);
    assertThat(counterAmountOf(euroLeg)).isEqualByComparingTo("150.00");

    // Remove Franc's file the way ImportStagingService does: clean up first, then delete — the
    // on-delete-set-null FK (V21) clears euroLeg's mirror_pair_id as part of the cascade.
    importMirrorRepository.clearCounterAmountOfMirrorsIn(francFile);
    jdbcClient
        .sql("delete from import_file where import_file_id = :id")
        .param("id", francFile)
        .update();

    assertThat(mirrorPairOf(euroLeg)).isNull();
    assertThat(counterAmountOf(euroLeg)).isNull();
  }
}
