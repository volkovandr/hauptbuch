package volkovandr.hauptbuch.statements;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 4, 30))).isEqualByComparingTo("100.00");
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 6, 30))).isEqualByComparingTo("67.50");
  }

  @Test
  void isZeroBeforeAnyLegAndForAnAccountWithNone() {
    assertThat(matches.ledgerBalance(own, LocalDate.of(2026, 1, 1))).isEqualByComparingTo("0");
    long empty = account("BankCcc-EUR");
    assertThat(matches.ledgerBalance(empty, LocalDate.of(2026, 6, 30))).isEqualByComparingTo("0");
    assertThat(matches.ledgerBalance(other, LocalDate.of(2026, 6, 30))).isEqualByComparingTo("-1.00");
  }
}
