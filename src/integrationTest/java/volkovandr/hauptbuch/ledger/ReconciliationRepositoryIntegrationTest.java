package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Integration tier (CLAUDE.md §6): the two writes a statement match makes on the ledger — a leg's
 * {@code reconciliation}, and the confirmation of the {@code pending_review} transaction owning it.
 * Each test is rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class ReconciliationRepositoryIntegrationTest {

  @Autowired TransactionRepository transactionRepository;
  @Autowired JdbcClient jdbcClient;

  private long accountId;

  @BeforeEach
  void seed() {
    accountId =
        jdbcClient
            .sql(
                "insert into account (name, type, currency_code) values ('BankAaa-EUR', 'asset',"
                    + " 'EUR') returning account_id")
            .query(Long.class)
            .single();
  }

  @Test
  void setReconciliationChangesOnlyTheNamedLegs() {
    long first = posting(transaction("confirmed", false), "unreconciled");
    long second = posting(transaction("confirmed", false), "unreconciled");

    transactionRepository.setReconciliation(List.of(first), "reconciled");

    assertThat(reconciliationOf(first)).isEqualTo("reconciled");
    assertThat(reconciliationOf(second)).isEqualTo("unreconciled");
  }

  @Test
  void confirmPendingOwningConfirmsOnlyLivePendingTransactionsOfTheNamedLegs() {
    long pending = transaction("pending_review", false);
    long pendingLeg = posting(pending, "unreconciled");
    long otherPendingLeg = posting(transaction("pending_review", false), "unreconciled");
    long voidedLeg = posting(transaction("pending_review", true), "unreconciled");

    int confirmed = transactionRepository.confirmPendingOwning(List.of(pendingLeg, voidedLeg));

    assertThat(confirmed).isEqualTo(1);
    assertThat(lifecycleOf(pendingLeg)).isEqualTo("confirmed");
    assertThat(lifecycleOf(otherPendingLeg)).isEqualTo("pending_review");
    assertThat(lifecycleOf(voidedLeg)).isEqualTo("pending_review");
  }

  private long transaction(String lifecycle, boolean deleted) {
    return jdbcClient
        .sql(
            "insert into transaction (date, lifecycle, deleted_at) values (date '2026-05-02',"
                + " :l, case when :d then now() end) returning transaction_id")
        .param("l", lifecycle)
        .param("d", deleted)
        .query(Long.class)
        .single();
  }

  private long posting(long transactionId, String reconciliation) {
    return jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount, reconciliation) values"
                + " (:t, :a, 5, :r) returning posting_id")
        .param("t", transactionId)
        .param("a", accountId)
        .param("r", reconciliation)
        .query(Long.class)
        .single();
  }

  private String reconciliationOf(long postingId) {
    return jdbcClient
        .sql("select reconciliation from posting where posting_id = :p")
        .param("p", postingId)
        .query(String.class)
        .single();
  }

  private String lifecycleOf(long postingId) {
    return jdbcClient
        .sql(
            "select t.lifecycle from transaction t join posting p on p.transaction_id ="
                + " t.transaction_id where p.posting_id = :p")
        .param("p", postingId)
        .query(String.class)
        .single();
  }
}
