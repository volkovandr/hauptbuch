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
 * SQL-logic tier (CLAUDE.md §6): {@link StatementMatchRepository#ledgerBalance}, the ledger side of
 * the statement balance checks. Crafted legs cover the inclusive date bound, other accounts,
 * soft-deleted transactions and an account with no legs.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StatementLedgerBalanceSqlLogicTest {

  @Autowired JdbcClient jdbcClient;
  @Autowired StatementMatchRepository matches;

  private long own;
  private long other;

  @BeforeEach
  void seed() {
    own = account("BankAaa-EUR");
    other = account("BankBbb-EUR");
    leg(LocalDate.of(2026, 4, 30), own, "100.00", false);
    leg(LocalDate.of(2026, 5, 10), own, "-20.00", false);
    leg(LocalDate.of(2026, 5, 31), own, "-5.50", false);
    leg(LocalDate.of(2026, 6, 1), own, "-7.00", false);
    leg(LocalDate.of(2026, 5, 15), own, "-999.00", true);
    leg(LocalDate.of(2026, 5, 15), other, "-1.00", false);
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

  private void leg(LocalDate date, long account, String amount, boolean deleted) {
    long txn =
        jdbcClient
            .sql(
                "insert into transaction (date, deleted_at)"
                    + " values (:d, case when :x then now() end) returning transaction_id")
            .param("d", date)
            .param("x", deleted)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation)"
                + " values (:t, :a, :m, 'unreconciled')")
        .param("t", txn)
        .param("a", account)
        .param("m", new BigDecimal(amount))
        .update();
  }

  @Test
  void sumsLiveLegsOnTheAccountThroughTheDateInclusive() {
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 5, 31))).isEqualByComparingTo("74.50");
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 4, 30)))
        .isEqualByComparingTo("100.00");
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 6, 30))).isEqualByComparingTo("67.50");
  }

  @Test
  void isZeroBeforeAnyLegAndForAnAccountWithNone() {
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 1, 1))).isEqualByComparingTo("0");
    long empty = account("BankCcc-EUR");
    assertThat(matches.ledgerBalance(empty, LocalDate.of(2026, 6, 30))).isEqualByComparingTo("0");
    assertThat(matches.ledgerBalance(other, LocalDate.of(2026, 6, 30)))
        .isEqualByComparingTo("-1.00");
  }

  private long statement(long account, String start, String end, boolean deleted) {
    long profile =
        jdbcClient
            .sql(
                "insert into statement_profile (name, format) values ('p' || random(), 'csv')"
                    + " returning statement_profile_id")
            .query(Long.class)
            .single();
    return jdbcClient
        .sql(
            "insert into statement (statement_profile_id, account_id, state, original_filename,"
                + " file_path, period_start, period_end, deleted_at)"
                + " values (:p, :a, 'new', 'f', 'f', :s, :e, case when :x then now() end)"
                + " returning statement_id")
        .param("p", profile)
        .param("a", account)
        .param("s", LocalDate.parse(start))
        .param("e", LocalDate.parse(end))
        .param("x", deleted)
        .query(Long.class)
        .single();
  }

  private void match(long statementId, LocalDate legDate, String amount) {
    long posting =
        jdbcClient
            .sql(
                "select p.posting_id from posting p join transaction t"
                    + " on t.transaction_id = p.transaction_id"
                    + " where p.account_id = :a and t.date = :d and p.amount = :m")
            .param("a", own)
            .param("d", legDate)
            .param("m", new BigDecimal(amount))
            .query(Long.class)
            .single();
    long line =
        jdbcClient
            .sql(
                "insert into statement_line (statement_id, sort_order, raw_text)"
                    + " values (:s, 0, 'r') returning statement_line_id")
            .param("s", statementId)
            .query(Long.class)
            .single();
    jdbcClient
        .sql(
            "insert into statement_match (statement_line_id, statement_id, posting_id)"
                + " values (:l, :s, :p)")
        .param("l", line)
        .param("s", statementId)
        .param("p", posting)
        .update();
  }

  @Test
  void neighbourMatchesAreThoseOnOtherLiveStatementsOfTheSameAccount() {
    long may = statement(own, "2026-05-01", "2026-05-31", false);
    long gone = statement(own, "2026-04-01", "2026-04-30", true);
    match(may, LocalDate.of(2026, 6, 1), "-7.00");
    match(gone, LocalDate.of(2026, 4, 30), "100.00");
    long june = statement(own, "2026-06-01", "2026-06-30", false);
    long foreign = statement(other, "2026-05-01", "2026-05-31", false);
    leg(LocalDate.of(2026, 5, 20), other, "-3.00", false);
    jdbcClient
        .sql(
            "insert into statement_line (statement_id, sort_order, raw_text)"
                + " values (:s, 0, 'r')")
        .param("s", foreign)
        .update();

    List<NeighbourMatch> seenFromJune = matches.findNeighbourMatches(june);
    assertThat(seenFromJune).hasSize(1);
    assertThat(seenFromJune.get(0).transactionDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(seenFromJune.get(0).amount()).isEqualByComparingTo("-7.00");
    assertThat(seenFromJune.get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 5, 31));
    assertThat(matches.findNeighbourMatches(may)).isEmpty();
  }
}
