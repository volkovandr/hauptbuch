package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
 * SQL-logic tier (CLAUDE.md §6): {@link StatementRepository#findPreviousId} and {@link
 * StatementRepository#findNextId} — the neighbours of a statement on the same account, by start
 * date (the period start, else the earliest line date), with the tie rules of issue 17. Boots
 * Spring so the queries are the real repository SQL; raw {@link JdbcClient} seeds the rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementNeighboursSqlLogicTest {

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

  private long statement(long accountId, LocalDate start, LocalDate end) {
    return jdbcClient
        .sql(
            """
            insert into statement
              (statement_profile_id, account_id, state, original_filename, file_path,
               period_start, period_end)
            values (:p, :a, 'new', 'x.csv', 'x', :s, :e)
            returning statement_id
            """)
        .param("p", profileId)
        .param("a", accountId)
        .param("s", start)
        .param("e", end)
        .query(Long.class)
        .single();
  }

  private void line(long statementId, int order, LocalDate bookingDate) {
    jdbcClient
        .sql(
            "insert into statement_line (statement_id, sort_order, booking_date)"
                + " values (:s, :o, :d)")
        .param("s", statementId)
        .param("o", order)
        .param("d", bookingDate)
        .update();
  }

  private void delete(long statementId) {
    jdbcClient
        .sql("update statement set deleted_at = now() where statement_id = :s")
        .param("s", statementId)
        .update();
  }

  private static LocalDate day(int month, int day) {
    return LocalDate.of(2026, month, day);
  }

  @Test
  void neighboursAreTheClosestStartsEitherSideAcrossGaps() {
    long february = statement(accountA, day(2, 1), day(2, 28));
    long april = statement(accountA, day(4, 1), day(4, 30));
    long june = statement(accountA, day(6, 1), day(6, 30));

    assertThat(statements.findPreviousId(april)).contains(february);
    assertThat(statements.findNextId(april)).contains(june);
    assertThat(statements.findPreviousId(february)).isEmpty();
    assertThat(statements.findNextId(june)).isEmpty();
  }

  @Test
  void otherAccountsAndDeletedStatementsAreNotNeighbours() {
    long march = statement(accountA, day(3, 1), day(3, 31));
    long otherAccount = statement(accountB, day(4, 1), day(4, 30));
    long deleted = statement(accountA, day(4, 1), day(4, 30));
    delete(deleted);
    long may = statement(accountA, day(5, 1), day(5, 31));

    assertThat(statements.findNextId(march)).contains(may);
    assertThat(statements.findPreviousId(may)).contains(march);
    assertThat(statements.findNextId(otherAccount)).isEmpty();
    assertThat(statements.findPreviousId(otherAccount)).isEmpty();
  }

  @Test
  void aStatementWithoutPeriodStartIsPlacedByItsEarliestLine() {
    long march = statement(accountA, day(3, 1), day(3, 31));
    long undated = statement(accountA, null, null);
    line(undated, 0, day(4, 20));
    line(undated, 1, day(4, 3));
    line(undated, 2, null);
    long june = statement(accountA, day(6, 1), day(6, 30));

    assertThat(statements.findNextId(march)).contains(undated);
    assertThat(statements.findPreviousId(june)).contains(undated);
    assertThat(statements.findPreviousId(undated)).contains(march);
    assertThat(statements.findNextId(undated)).contains(june);
  }

  @Test
  void aStatementWithNoDateAtAllHasNoNeighboursAndIsNobodysNeighbour() {
    long march = statement(accountA, day(3, 1), day(3, 31));
    long blank = statement(accountA, null, null);
    line(blank, 0, null);
    long may = statement(accountA, day(5, 1), day(5, 31));

    assertThat(statements.findPreviousId(blank)).isEmpty();
    assertThat(statements.findNextId(blank)).isEmpty();
    assertThat(statements.findNextId(march)).contains(may);
    assertThat(statements.findPreviousId(may)).contains(march);
  }

  @Test
  void previousOfTwoStatementsStartingTogetherEndsClosestBeforeTheCurrentStart() {
    statement(accountA, day(3, 1), day(3, 15));
    long fullMonth = statement(accountA, day(3, 1), day(3, 31));
    statement(accountA, day(3, 1), day(4, 10));
    long april = statement(accountA, day(4, 1), day(4, 30));

    assertThat(statements.findPreviousId(april)).contains(fullMonth);
  }

  @Test
  void previousPrefersAnEndNotAfterTheCurrentStartOverOneThatOverlapsIt() {
    statement(accountA, day(3, 1), day(4, 10));
    long april = statement(accountA, day(4, 1), day(4, 30));
    long shortOne = statement(accountA, day(3, 1), day(3, 15));

    assertThat(statements.findPreviousId(april)).contains(shortOne);
  }

  @Test
  void previousFallsBackToTheSmallerIdWhenStartAndEndAreTheSame() {
    long first = statement(accountA, day(3, 1), day(3, 31));
    statement(accountA, day(3, 1), day(3, 31));
    long april = statement(accountA, day(4, 1), day(4, 30));

    assertThat(statements.findPreviousId(april)).contains(first);
  }

  @Test
  void nextOfTwoStatementsStartingTogetherIsTheSmallerId() {
    long march = statement(accountA, day(3, 1), day(3, 31));
    long first = statement(accountA, day(4, 1), day(4, 30));
    statement(accountA, day(4, 1), day(4, 15));

    assertThat(statements.findNextId(march)).contains(first);
  }

  @Test
  void statementsWithTheSameStartAreNotNeighboursOfEachOther() {
    long first = statement(accountA, day(3, 1), day(3, 31));
    long second = statement(accountA, day(3, 1), day(3, 15));

    assertThat(statements.findPreviousId(first)).isEmpty();
    assertThat(statements.findNextId(first)).isEmpty();
    assertThat(statements.findPreviousId(second)).isEmpty();
    assertThat(statements.findNextId(second)).isEmpty();
  }
}
