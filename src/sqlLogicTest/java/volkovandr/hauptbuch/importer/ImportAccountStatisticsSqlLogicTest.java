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
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ImportStatisticsRepository} — the per-account verification
 * device {@code perMoneyAccount} (import.md §9.4; plan e′) and Money's staged opening balances
 * {@code stagedOpeningBalances} (§5.1; plan c3). The logic lives in the SQL: a grouped aggregate
 * over {@code import_file} → {@code import_transaction} → {@code import_posting}, with the net sum
 * a <em>filtered</em> aggregate over the funding legs only so a transfer's mirror leg staged from
 * the other account's file is not double-counted; {@code stagedOpeningBalances} is the same
 * three-table join filtered to the opening-balance funding legs, ordered earliest-first per
 * account. Crafted staging rows via raw {@link JdbcClient}; the query under test is the real
 * repository. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportAccountStatisticsSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired ImportStatisticsRepository importStatisticsRepository;

  private long openSession() {
    return jdbcClient
        .sql("insert into import_session (state) values ('open') returning import_session_id")
        .query(Long.class)
        .single();
  }

  private long otherSession() {
    return jdbcClient
        .sql("insert into import_session (state) values ('committed') returning import_session_id")
        .query(Long.class)
        .single();
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

  private long stageTransaction(long fileId, LocalDate date, boolean openingBalance) {
    return jdbcClient
        .sql(
            """
            insert into import_transaction (import_file_id, date, opening_balance)
            values (:f, :d, :ob)
            returning import_transaction_id
            """)
        .param("f", fileId)
        .param("d", date)
        .param("ob", openingBalance)
        .query(Long.class)
        .single();
  }

  private void stageLeg(
      long transactionId, String amount, boolean funding, String path, String acc) {
    jdbcClient
        .sql(
            """
            insert into import_posting
              (import_transaction_id, amount, money_category_path, money_account_name, funding)
            values (:t, :a, :p, :acc, :fund)
            """)
        .param("t", transactionId)
        .param("a", new BigDecimal(amount))
        .param("p", path)
        .param("acc", acc)
        .param("fund", funding)
        .update();
  }

  /** A plain category transaction: the category leg, then the synthesised funding leg (§7). */
  private void stagePlain(long fileId, LocalDate date, String fundingAmount, String moneyAccount) {
    long id = stageTransaction(fileId, date, false);
    stageLeg(id, new BigDecimal(fundingAmount).negate().toPlainString(), false, "Food", null);
    stageLeg(id, fundingAmount, true, null, moneyAccount);
  }

  @Test
  void countsNetsAndDatesOneAccountsFundingLegs() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    stagePlain(file, LocalDate.of(2004, 7, 1), "-12.34", "Current Account");
    stagePlain(file, LocalDate.of(2010, 3, 15), "-5.00", "Current Account");
    stagePlain(file, LocalDate.of(2026, 8, 30), "100.00", "Current Account");

    List<ImportAccountStatistics> stats = importStatisticsRepository.perMoneyAccount(session);

    assertThat(stats).hasSize(1);
    ImportAccountStatistics current = stats.get(0);
    assertThat(current.moneyAccountName()).isEqualTo("Current Account");
    assertThat(current.transactionCount()).isEqualTo(3);
    assertThat(current.netSum()).isEqualByComparingTo("82.66");
    assertThat(current.firstDate()).isEqualTo(LocalDate.of(2004, 7, 1));
    assertThat(current.lastDate()).isEqualTo(LocalDate.of(2026, 8, 30));
  }

  @Test
  void netSumIncludesTheOpeningBalanceFundingLegNotItsSelfTransferLeg() {
    long session = openSession();
    long file = stageFile(session, "Current Account");

    // Money's opening balance is a self-transfer: a funding leg +1000 and a leg naming the same
    // account -1000. Only the funding leg counts (§9.4), so the pair does not net to zero.
    long opening = stageTransaction(file, LocalDate.of(2004, 7, 1), true);
    stageLeg(opening, "-1000.00", false, null, "Current Account");
    stageLeg(opening, "1000.00", true, null, "Current Account");

    stagePlain(file, LocalDate.of(2004, 7, 2), "-5.00", "Current Account");

    List<ImportAccountStatistics> stats = importStatisticsRepository.perMoneyAccount(session);

    assertThat(stats).hasSize(1);
    assertThat(stats.get(0).transactionCount()).isEqualTo(2);
    assertThat(stats.get(0).netSum()).isEqualByComparingTo("995.00");
  }

  @Test
  void transferMirrorLegFromAnotherFileIsNotCounted() {
    long session = openSession();
    long current = stageFile(session, "Current Account");
    long savings = stageFile(session, "Savings");

    // A €20 transfer from Current Account to Savings, staged from Current Account's file.
    long out = stageTransaction(current, LocalDate.of(2005, 1, 1), false);
    stageLeg(out, "20.00", false, null, "Savings");
    stageLeg(out, "-20.00", true, null, "Current Account");

    // The same transfer as it appears in Savings' own export: the funding leg lands on Savings, the
    // mirror leg names Current Account. That mirror leg must not be added to Current Account here.
    long in = stageTransaction(savings, LocalDate.of(2005, 1, 1), false);
    stageLeg(in, "-20.00", false, null, "Current Account");
    stageLeg(in, "20.00", true, null, "Savings");

    List<ImportAccountStatistics> stats = importStatisticsRepository.perMoneyAccount(session);

    assertThat(stats)
        .extracting(ImportAccountStatistics::moneyAccountName)
        .containsExactly("Current Account", "Savings");
    assertThat(stats.get(0).netSum()).isEqualByComparingTo("-20.00");
    assertThat(stats.get(1).netSum()).isEqualByComparingTo("20.00");
  }

  @Test
  void foldsFilesSharingOneMoneyAccountName() {
    long session = openSession();
    long first = stageFile(session, "Current Account");
    long second = stageFile(session, "Current Account");
    stagePlain(first, LocalDate.of(2004, 7, 1), "-10.00", "Current Account");
    stagePlain(second, LocalDate.of(2004, 8, 1), "-5.00", "Current Account");

    List<ImportAccountStatistics> stats = importStatisticsRepository.perMoneyAccount(session);

    assertThat(stats).hasSize(1);
    assertThat(stats.get(0).transactionCount()).isEqualTo(2);
    assertThat(stats.get(0).netSum()).isEqualByComparingTo("-15.00");
    assertThat(stats.get(0).firstDate()).isEqualTo(LocalDate.of(2004, 7, 1));
    assertThat(stats.get(0).lastDate()).isEqualTo(LocalDate.of(2004, 8, 1));
  }

  @Test
  void splitTransactionCountsOnceAndSumsItsFundingLegOnce() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    long split = stageTransaction(file, LocalDate.of(2004, 7, 1), false);
    stageLeg(split, "60.00", false, "Food", null);
    stageLeg(split, "40.00", false, "Fuel", null);
    stageLeg(split, "-100.00", true, null, "Current Account");

    List<ImportAccountStatistics> stats = importStatisticsRepository.perMoneyAccount(session);

    assertThat(stats).hasSize(1);
    assertThat(stats.get(0).transactionCount()).isEqualTo(1);
    assertThat(stats.get(0).netSum()).isEqualByComparingTo("-100.00");
  }

  @Test
  void scopesToTheGivenSession() {
    long session = openSession();
    long other = otherSession();
    stagePlain(
        stageFile(session, "Current Account"),
        LocalDate.of(2004, 7, 1),
        "-10.00",
        "Current Account");
    stagePlain(
        stageFile(other, "Current Account"),
        LocalDate.of(2004, 7, 1),
        "-999.00",
        "Current Account");

    List<ImportAccountStatistics> stats = importStatisticsRepository.perMoneyAccount(session);

    assertThat(stats).hasSize(1);
    assertThat(stats.get(0).netSum()).isEqualByComparingTo("-10.00");
  }

  @Test
  void emptySessionYieldsNoRows() {
    assertThat(importStatisticsRepository.perMoneyAccount(openSession())).isEmpty();
  }

  @Test
  void stagedOpeningBalancesReadsTheFundingLegOfEveryOpeningBalanceTransaction() {
    long session = openSession();
    long current = stageFile(session, "Current Account");

    long currentOpening = stageTransaction(current, LocalDate.of(2004, 7, 1), true);
    stageLeg(currentOpening, "-1000.00", false, null, "Current Account");
    stageLeg(currentOpening, "1000.00", true, null, "Current Account");
    stagePlain(current, LocalDate.of(2004, 7, 2), "-5.00", "Current Account");

    long savings = stageFile(session, "Savings");
    long savingsOpening = stageTransaction(savings, LocalDate.of(2006, 1, 1), true);
    stageLeg(savingsOpening, "-250.00", false, null, "Savings");
    stageLeg(savingsOpening, "250.00", true, null, "Savings");

    assertThat(importStatisticsRepository.stagedOpeningBalances(session))
        .satisfiesExactly(
            a -> {
              assertThat(a.moneyAccountName()).isEqualTo("Current Account");
              assertThat(a.date()).isEqualTo(LocalDate.of(2004, 7, 1));
              assertThat(a.amount()).isEqualByComparingTo("1000.00");
            },
            b -> {
              assertThat(b.moneyAccountName()).isEqualTo("Savings");
              assertThat(b.date()).isEqualTo(LocalDate.of(2006, 1, 1));
              assertThat(b.amount()).isEqualByComparingTo("250.00");
            });
  }

  @Test
  void stagedOpeningBalancesOrdersAnAccountsTwoFilesEarliestFirst() {
    long session = openSession();
    long first = stageFile(session, "Current Account");
    long second = stageFile(session, "Current Account");

    long later = stageTransaction(second, LocalDate.of(2008, 5, 1), true);
    stageLeg(later, "-9.00", false, null, "Current Account");
    stageLeg(later, "9.00", true, null, "Current Account");

    long earlier = stageTransaction(first, LocalDate.of(2004, 7, 1), true);
    stageLeg(earlier, "-1000.00", false, null, "Current Account");
    stageLeg(earlier, "1000.00", true, null, "Current Account");

    assertThat(importStatisticsRepository.stagedOpeningBalances(session))
        .extracting(ImportStagedOpeningBalance::date)
        .containsExactly(LocalDate.of(2004, 7, 1), LocalDate.of(2008, 5, 1));
  }

  @Test
  void stagedOpeningBalancesIgnoresOrdinaryTransactionsAndOtherSessions() {
    long session = openSession();
    long other = otherSession();
    stagePlain(
        stageFile(session, "Current Account"),
        LocalDate.of(2004, 7, 1),
        "-10.00",
        "Current Account");

    long otherOpening =
        stageTransaction(stageFile(other, "Current Account"), LocalDate.of(2004, 1, 1), true);
    stageLeg(otherOpening, "-1.00", false, null, "Current Account");
    stageLeg(otherOpening, "1.00", true, null, "Current Account");

    assertThat(importStatisticsRepository.stagedOpeningBalances(session)).isEmpty();
  }
}
