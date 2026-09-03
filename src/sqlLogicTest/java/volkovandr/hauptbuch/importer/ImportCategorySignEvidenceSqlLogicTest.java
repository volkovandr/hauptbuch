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
 * SQL-logic tier (CLAUDE.md §6): {@link ImportStatisticsRepository#perCategoryPath} — the category
 * map's sign evidence (import.md §5.2; plan d1). The logic lives in the SQL: a grouped, filtered
 * aggregate over {@code import_file} → {@code import_transaction} → {@code import_posting},
 * counting the positive and negative staged lines per Money path so the review can label each path
 * expense-vs-income. Files sharing a Money account name and both files' lines for one path
 * accumulate; other sessions are excluded. Crafted rows via raw {@link JdbcClient}; the query under
 * test is the real repository. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportCategorySignEvidenceSqlLogicTest {

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

  private long stageTransaction(long fileId, LocalDate date) {
    return stageTransaction(fileId, date, "ready");
  }

  private long stageTransaction(long fileId, LocalDate date, String state) {
    return jdbcClient
        .sql(
            "insert into import_transaction (import_file_id, date, state) values (:f, :d, :s)"
                + " returning import_transaction_id")
        .param("f", fileId)
        .param("d", date)
        .param("s", state)
        .query(Long.class)
        .single();
  }

  private void stageLeg(long transactionId, String amount, String path, String account) {
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
        .param("acc", account)
        .param("fund", account != null)
        .update();
  }

  /** A one-line category transaction: the category leg (Hauptbuch sign) + the funding leg. */
  private void stageCategory(long fileId, LocalDate date, String categoryAmount, String path) {
    long id = stageTransaction(fileId, date);
    stageLeg(id, categoryAmount, path, null);
    stageLeg(id, new BigDecimal(categoryAmount).negate().toPlainString(), null, "Current Account");
  }

  @Test
  void countsPositiveAndNegativeStagedLinesPerPath() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    // Food: two spends (positive category legs) and one refund (negative).
    stageCategory(file, LocalDate.of(2004, 7, 1), "12.34", "Food");
    stageCategory(file, LocalDate.of(2004, 7, 2), "5.00", "Food");
    stageCategory(file, LocalDate.of(2004, 7, 3), "-2.00", "Food");
    // Salary: two receipts only.
    stageCategory(file, LocalDate.of(2004, 7, 25), "-2000.00", "Salary");
    stageCategory(file, LocalDate.of(2004, 8, 25), "-2100.00", "Salary");

    List<ImportCategorySignEvidence> evidence = importStatisticsRepository.perCategoryPath(session);

    assertThat(evidence)
        .satisfiesExactly(
            food -> {
              assertThat(food.moneyPath()).isEqualTo("Food");
              assertThat(food.debitLineCount()).isEqualTo(2);
              assertThat(food.creditLineCount()).isEqualTo(1);
            },
            salary -> {
              assertThat(salary.moneyPath()).isEqualTo("Salary");
              assertThat(salary.debitLineCount()).isZero();
              assertThat(salary.creditLineCount()).isEqualTo(2);
            });
  }

  @Test
  void accumulatesAcrossFilesAndIgnoresFundingAndTransferLegs() {
    long session = openSession();
    long current = stageFile(session, "Current Account");
    long savings = stageFile(session, "Savings");
    stageCategory(current, LocalDate.of(2004, 7, 1), "10.00", "Food");
    stageCategory(savings, LocalDate.of(2004, 7, 2), "7.00", "Food");

    // A transfer between the two accounts — no category path, must not appear.
    long transfer = stageTransaction(current, LocalDate.of(2004, 7, 3));
    stageLeg(transfer, "20.00", null, "Savings");
    stageLeg(transfer, "-20.00", null, "Current Account");

    List<ImportCategorySignEvidence> evidence = importStatisticsRepository.perCategoryPath(session);

    assertThat(evidence)
        .singleElement()
        .satisfies(
            food -> {
              assertThat(food.moneyPath()).isEqualTo("Food");
              assertThat(food.debitLineCount()).isEqualTo(2);
              assertThat(food.creditLineCount()).isZero();
            });
  }

  @Test
  void scopesToTheGivenSession() {
    long session = openSession();
    long other = otherSession();
    stageCategory(stageFile(session, "Current Account"), LocalDate.of(2004, 7, 1), "10.00", "Food");
    stageCategory(stageFile(other, "Current Account"), LocalDate.of(2004, 7, 1), "99.00", "Food");

    assertThat(importStatisticsRepository.perCategoryPath(session))
        .singleElement()
        .satisfies(food -> assertThat(food.debitLineCount()).isEqualTo(1));
  }

  @Test
  void ignoresLinesOnMirroredOrExcludedTransactions() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    stageCategory(file, LocalDate.of(2004, 7, 1), "10.00", "Food");

    // A mirrored sighting and an excluded one, both carrying a Food leg — neither will book (§6),
    // so neither counts toward the hint.
    long mirrored = stageTransaction(file, LocalDate.of(2004, 7, 2), "mirrored");
    stageLeg(mirrored, "99.00", "Food", null);
    long excluded = stageTransaction(file, LocalDate.of(2004, 7, 3), "excluded");
    stageLeg(excluded, "-99.00", "Food", null);

    assertThat(importStatisticsRepository.perCategoryPath(session))
        .singleElement()
        .satisfies(
            food -> {
              assertThat(food.debitLineCount()).isEqualTo(1);
              assertThat(food.creditLineCount()).isZero();
            });
  }

  @Test
  void emptySessionYieldsNoRows() {
    assertThat(importStatisticsRepository.perCategoryPath(openSession())).isEmpty();
  }
}
