package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

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
import volkovandr.hauptbuch.statements.repository.StatementRepository;

/**
 * SQL-logic tier (CLAUDE.md §6): {@link StatementRepository#findLiveRows} — the Statements list
 * query, which joins the account and the profile, counts lines and problem lines per statement, and
 * orders newest first. Crafted statements cover the counts (including a statement with no lines),
 * the account filter, soft-deleted statements, and the ordering with and without a period. Boots
 * Spring so the query is the real repository SQL; raw {@link JdbcClient} seeds the rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementRowsSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired StatementRepository statements;

  private long accountA;
  private long accountB;
  private long profileId;

  @BeforeEach
  void seed() {
    accountA = account("BankAaa-EUR");
    accountB = account("BankBbb-EUR");
    profileId =
        jdbcClient
            .sql(
                "insert into statement_profile (name, format) values ('BankAaa CSV', 'csv')"
                    + " returning statement_profile_id")
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

  private long statement(long accountId, String file, LocalDate end, boolean deleted) {
    return jdbcClient
        .sql(
            """
            insert into statement
              (statement_profile_id, account_id, state, original_filename, file_path, period_end,
               deleted_at)
            values
              (:p, :a, 'new', :f, 'x', :e, case when :d then now() end)
            returning statement_id
            """)
        .param("p", profileId)
        .param("a", accountId)
        .param("f", file)
        .param("e", end)
        .param("d", deleted)
        .query(Long.class)
        .single();
  }

  private void line(long statementId, int order, String problem) {
    jdbcClient
        .sql("insert into statement_line (statement_id, sort_order, problem) values (:s, :o, :p)")
        .param("s", statementId)
        .param("o", order)
        .param("p", problem)
        .update();
  }

  @Test
  void countsLinesAndProblemLinesPerStatement() {
    long withProblems = statement(accountA, "may.csv", LocalDate.of(2026, 5, 31), false);
    line(withProblems, 0, null);
    line(withProblems, 1, "Unreadable amount 'x'");
    line(withProblems, 2, "Currency USD, but the account is in EUR");
    long empty = statement(accountA, "empty.csv", LocalDate.of(2026, 4, 30), false);

    List<StatementRow> rows = statements.findLiveRows(null);

    StatementRow counted = rowOf(rows, withProblems);
    assertThat(counted.lineCount()).isEqualTo(3);
    assertThat(counted.problemCount()).isEqualTo(2);
    assertThat(counted.accountName()).isEqualTo("BankAaa-EUR");
    assertThat(counted.profileName()).isEqualTo("BankAaa CSV");
    assertThat(rowOf(rows, empty).lineCount()).isZero();
    assertThat(rowOf(rows, empty).problemCount()).isZero();
  }

  @Test
  void ordersNewestPeriodFirstWithUndatedStatementsLast() {
    long april = statement(accountA, "april.csv", LocalDate.of(2026, 4, 30), false);
    long undated = statement(accountA, "undated.csv", null, false);
    long may = statement(accountA, "may.csv", LocalDate.of(2026, 5, 31), false);

    assertThat(statements.findLiveRows(null))
        .extracting(StatementRow::statementId)
        .containsExactly(may, april, undated);
  }

  @Test
  void filtersByAccountAndHidesDeletedStatements() {
    long onA = statement(accountA, "a.csv", LocalDate.of(2026, 5, 31), false);
    long onB = statement(accountB, "b.csv", LocalDate.of(2026, 5, 31), false);
    statement(accountA, "gone.csv", LocalDate.of(2026, 6, 30), true);

    assertThat(statements.findLiveRows(accountA))
        .extracting(StatementRow::statementId)
        .containsExactly(onA);
    assertThat(statements.findLiveRows(accountB))
        .extracting(StatementRow::statementId)
        .containsExactly(onB);
    assertThat(statements.findLiveRows(null))
        .extracting(StatementRow::statementId)
        .containsExactlyInAnyOrder(onA, onB);
  }

  private static StatementRow rowOf(List<StatementRow> rows, long statementId) {
    return rows.stream().filter(r -> r.statementId() == statementId).findFirst().orElseThrow();
  }
}
