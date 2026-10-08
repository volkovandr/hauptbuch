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
 * SQL-logic tier (CLAUDE.md §6): which statements match the legs of a transaction, for the register
 * dock's reconciled-leg notice (statements.md §6.4) — through the real {@link
 * StatementMatchRepository}, over crafted matches on one and on both legs, an unmatched leg and a
 * deleted statement.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementLegNoticesSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired StatementMatchRepository matcher;

  private long profile;
  private long bankAaa;
  private long bankBbb;
  private long transaction;
  private long legAaa;
  private long legBbb;

  @BeforeEach
  void seed() {
    profile =
        id(
            "insert into statement_profile (name, format) values ('BankAaa CSV', 'csv')"
                + " returning statement_profile_id");
    bankAaa = account("BankAaa-EUR", "asset");
    bankBbb = account("BankBbb-EUR", "asset");
    transaction =
        id("insert into transaction (date) values ('2026-05-10') returning transaction_id");
    legAaa = posting(bankAaa, "-10");
    legBbb = posting(bankBbb, "10");
  }

  private long id(String sql) {
    return jdbcClient.sql(sql).query(Long.class).single();
  }

  private long account(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR')"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private long posting(long account, String amount) {
    return jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation)"
                + " values (:t, :a, :m, 'reconciled') returning posting_id")
        .param("t", transaction)
        .param("a", account)
        .param("m", new BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  /** A statement on the account, with one line matched to the posting. */
  private long matched(
      long account, String periodStart, String filename, long posting, boolean deleted) {
    long statement =
        jdbcClient
            .sql(
                "insert into statement (statement_profile_id, account_id, state, original_filename,"
                    + " file_path, period_start, deleted_at)"
                    + " values (:p, :a, 'new', :f, 'x', :s, case when :x then now() end)"
                    + " returning statement_id")
            .param("p", profile)
            .param("a", account)
            .param("f", filename)
            .param("s", periodStart == null ? null : LocalDate.parse(periodStart))
            .param("x", deleted)
            .query(Long.class)
            .single();
    long line =
        jdbcClient
            .sql(
                "insert into statement_line (statement_id, sort_order, booking_date, amount)"
                    + " values (:s, 0, '2026-05-10', 10) returning statement_line_id")
            .param("s", statement)
            .query(Long.class)
            .single();
    matcher.insertMatch(statement, line, posting);
    return statement;
  }

  @Test
  void transactionWithNoMatchedLegHasNoNotice() {
    assertThat(matcher.findStatementsOfTransaction(transaction)).isEmpty();
  }

  @Test
  void eachMatchedLegNamesItsAccountAndStatement() {
    matched(bankAaa, "2026-05-01", "may.csv", legAaa, false);
    matched(bankBbb, "2026-06-01", "june.csv", legBbb, false);

    List<StatementOfLeg> legs = matcher.findStatementsOfTransaction(transaction);

    assertThat(legs)
        .extracting(StatementOfLeg::notice)
        .containsExactly(
            "BankAaa-EUR leg reconciled — statement 2026-05",
            "BankBbb-EUR leg reconciled — statement 2026-06");
  }

  @Test
  void legMatchedOnTwoOverlappingStatementsNamesBoth() {
    matched(bankAaa, "2026-05-01", "may.csv", legAaa, false);
    matched(bankAaa, "2026-05-15", "mid-may.csv", legAaa, false);

    assertThat(matcher.findStatementsOfTransaction(transaction))
        .extracting(StatementOfLeg::periodStart)
        .containsExactly(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 15));
  }

  @Test
  void deletedStatementIsNotNamed() {
    matched(bankAaa, "2026-05-01", "may.csv", legAaa, true);

    assertThat(matcher.findStatementsOfTransaction(transaction)).isEmpty();
  }

  @Test
  void statementWithoutPeriodIsNamedByItsFile() {
    matched(bankAaa, null, "unknown.csv", legAaa, false);

    assertThat(matcher.findStatementsOfTransaction(transaction))
        .extracting(StatementOfLeg::notice)
        .containsExactly("BankAaa-EUR leg reconciled — statement unknown.csv");
  }
}
