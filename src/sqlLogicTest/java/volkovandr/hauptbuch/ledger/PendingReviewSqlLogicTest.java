package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.TestcontainersConfiguration;
import volkovandr.hauptbuch.ledger.repository.PendingReviewCount;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * SQL-logic tier (plan §1.5): {@link TransactionRepository#countPendingReview} — the main page's
 * "pending to review" line (register §2.3). The logic lives in the SQL: an aggregate over the live
 * {@code pending_review} rows with a filtered count for the overdue cut-off and the earliest date
 * (CLAUDE.md §6).
 *
 * <p>Boots Spring so the query under test is the real repository SQL; raw {@link JdbcClient} only
 * seeds. {@code @Transactional} rolls each test back on the reused container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PendingReviewSqlLogicTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
  private static final String PENDING = "pending_review";

  @Autowired JdbcClient jdbcClient;
  @Autowired TransactionRepository transactionRepository;

  private long insertTxn(LocalDate date, String lifecycle) {
    return jdbcClient
        .sql("insert into transaction (date, lifecycle) values (:d, :l) returning transaction_id")
        .param("d", date)
        .param("l", lifecycle)
        .query(Long.class)
        .single();
  }

  @Test
  void emptyBookCountsNothingAndHasNoEarliestDate() {
    assertThat(transactionRepository.countPendingReview(TODAY))
        .isEqualTo(new PendingReviewCount(0, 0, null));
  }

  @Test
  void countsLivePendingRowsWithThoseBeforeTodayAsOverdue() {
    insertTxn(LocalDate.of(2026, 9, 1), PENDING);
    // Today itself is not overdue; a future occurrence booked by lead time is not either.
    insertTxn(TODAY, PENDING);
    insertTxn(LocalDate.of(2026, 10, 3), PENDING);

    assertThat(transactionRepository.countPendingReview(TODAY))
        .isEqualTo(new PendingReviewCount(3, 1, LocalDate.of(2026, 9, 1)));
  }

  @Test
  void confirmedAndVoidedRowsMoveNeitherTheCountsNorTheEarliestDate() {
    insertTxn(LocalDate.of(2026, 9, 1), PENDING);
    insertTxn(LocalDate.of(2026, 8, 1), "confirmed");
    long voided = insertTxn(LocalDate.of(2026, 7, 1), PENDING);
    transactionRepository.softDelete(voided);

    assertThat(transactionRepository.countPendingReview(TODAY))
        .isEqualTo(new PendingReviewCount(1, 1, LocalDate.of(2026, 9, 1)));
  }
}
