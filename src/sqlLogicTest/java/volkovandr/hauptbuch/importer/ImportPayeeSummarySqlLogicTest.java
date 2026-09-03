package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.importer.repository.ImportStatisticsRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link ImportStatisticsRepository#payeeResolution} — the payee
 * figures the import review reports (import.md §5.3; plan d2). The logic lives in the SQL: a
 * case-folding grouping over {@code import_file} → {@code import_transaction} with filtered
 * aggregates for the distinct count, the seen-once count and the destroyed-name count. Files
 * sharing a Money account name accumulate; other sessions and rows that will not book ({@code
 * mirrored} / {@code excluded}, §6) are excluded. Crafted rows via raw {@link JdbcClient}; the
 * query under test is the real repository. {@code @Transactional} rolls each test back on the
 * reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ImportPayeeSummarySqlLogicTest {

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

  private void stageTransaction(long fileId, String payeeText) {
    stageTransaction(fileId, payeeText, false, "ready");
  }

  private void stageTransaction(long fileId, String payeeText, boolean destroyed, String state) {
    jdbcClient
        .sql(
            """
            insert into import_transaction
              (import_file_id, date, payee_text, payee_destroyed, state)
            values (:f, :d, :p, :destroyed, :s)
            """)
        .param("f", fileId)
        .param("d", LocalDate.of(2010, 1, 1))
        .param("p", payeeText)
        .param("destroyed", destroyed)
        .param("s", state)
        .update();
  }

  @Test
  void countsDistinctPayeesAndHowManyAreSeenOnce() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    // Rewe three times, Lidl twice, Aldi once.
    stageTransaction(file, "Rewe");
    stageTransaction(file, "Rewe");
    stageTransaction(file, "Rewe");
    stageTransaction(file, "Lidl");
    stageTransaction(file, "Lidl");
    stageTransaction(file, "Aldi");

    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(session);

    assertThat(summary.distinctPayees()).isEqualTo(3);
    assertThat(summary.seenOnce()).isEqualTo(1);
    assertThat(summary.destroyedRows()).isZero();
  }

  @Test
  void foldsCaseAndSurroundingWhitespaceWhenGrouping() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    // The importer's resolution is case-insensitive (import.md §5.3); the count must agree.
    // Surrounding whitespace of any kind (space, tab) folds too, and an internal run collapses.
    stageTransaction(file, "Rewe");
    stageTransaction(file, "REWE");
    stageTransaction(file, "  rewe ");
    stageTransaction(file, "\tRewe");
    stageTransaction(file, "Am  Markt"); // internal double space
    stageTransaction(file, "Am Markt");

    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(session);

    assertThat(summary.distinctPayees()).isEqualTo(2);
    assertThat(summary.seenOnce()).isZero();
  }

  @Test
  void keepsDifferentAddressesApart() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    // "Rewe" and "Rewe - Dortmund" parse to different payees — the count keeps them separate.
    stageTransaction(file, "Rewe");
    stageTransaction(file, "Rewe - Dortmund");

    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(session);

    assertThat(summary.distinctPayees()).isEqualTo(2);
    assertThat(summary.seenOnce()).isEqualTo(2);
  }

  @Test
  void countsDestroyedNamesSeparatelyAndDoesNotCountThemAsPayees() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    stageTransaction(file, "Rewe");
    // A wholly-destroyed name: payee_text null, payee_destroyed true (import.md §4.4) — these book
    // with no payee, and the review reports the count.
    stageTransaction(file, null, true, "ready");
    stageTransaction(file, null, true, "ready");
    // A transfer: no payee at all, not destroyed — neither a payee nor a destroyed row.
    stageTransaction(file, null, false, "ready");

    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(session);

    assertThat(summary.distinctPayees()).isEqualTo(1);
    assertThat(summary.seenOnce()).isEqualTo(1);
    assertThat(summary.destroyedRows()).isEqualTo(2);
  }

  @Test
  void accumulatesAcrossFilesInTheCampaign() {
    long session = openSession();
    long current = stageFile(session, "Current Account");
    long savings = stageFile(session, "Savings");
    stageTransaction(current, "Rewe");
    stageTransaction(savings, "Rewe");
    stageTransaction(savings, "Lidl");

    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(session);

    assertThat(summary.distinctPayees()).isEqualTo(2);
    assertThat(summary.seenOnce()).isEqualTo(1);
  }

  @Test
  void ignoresRowsThatWillNotBookAndOtherSessions() {
    long session = openSession();
    long file = stageFile(session, "Current Account");
    stageTransaction(file, "Rewe");
    stageTransaction(file, "Mirrored Payee", false, "mirrored");
    stageTransaction(file, null, true, "excluded");

    // A different session's staged data must not leak in.
    long other = otherSession();
    long otherFile = stageFile(other, "Current Account");
    stageTransaction(otherFile, "Aldi");
    stageTransaction(otherFile, null, true, "ready");

    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(session);

    assertThat(summary.distinctPayees()).isEqualTo(1);
    assertThat(summary.seenOnce()).isEqualTo(1);
    assertThat(summary.destroyedRows()).isZero();
  }

  @Test
  void emptySessionYieldsTheEmptySummary() {
    ImportPayeeSummary summary = importStatisticsRepository.payeeResolution(openSession());

    assertThat(summary).isEqualTo(ImportPayeeSummary.EMPTY);
    assertThat(summary.empty()).isTrue();
  }
}
