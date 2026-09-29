package volkovandr.hauptbuch.ledger;

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
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Integration tier (plan §1.5): the {@link TransactionRepository} reads and the one hard delete a
 * recurring template's edit needs (data-model §14.3, ADR 0002, recurring sub-plan slice e). Rows
 * are seeded by raw JDBC; {@code @Transactional} rolls each test back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class OccurrenceRepositoryIntegrationTest {

  private static final String PENDING = "pending_review";
  private static final String CONFIRMED = "confirmed";
  private static final LocalDate JUN_1 = LocalDate.of(2026, 6, 1);
  private static final LocalDate JUL_1 = LocalDate.of(2026, 7, 1);
  private static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1);
  private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

  @Autowired JdbcClient jdbcClient;
  @Autowired TransactionRepository transactionRepository;

  private long cash;
  private long food;
  private long template;

  @BeforeEach
  void setUp() {
    cash = insertAccount("Cash", "asset");
    food = insertAccount("Food", "expense");
    template = insertTemplate();
  }

  private long insertAccount(String name, String type) {
    return jdbcClient
        .sql(
            "insert into account (name, type, currency_code) values (:n, :t, 'EUR')"
                + " returning account_id")
        .param("n", name)
        .param("t", type)
        .query(Long.class)
        .single();
  }

  private long insertTemplate() {
    return jdbcClient
        .sql(
            """
            insert into recurring_template
              (name, start_date, cadence_unit, cadence_n, confirmation, booked_through, account_id)
            values ('Streaming', date '2026-06-01', 'month', 1, 'review', date '2026-05-31', :a)
            returning recurring_template_id
            """)
        .param("a", cash)
        .query(Long.class)
        .single();
  }

  /** A Cash → Food spend dated on its occurrence, stamped when {@code templateId} is non-null. */
  private long occurrence(Long templateId, LocalDate date, String lifecycle) {
    long txnId =
        jdbcClient
            .sql(
                """
                insert into transaction (date, lifecycle, recurring_template_id, occurrence_date)
                values (:d, :l, cast(:r as bigint),
                        case when cast(:r as bigint) is null then null else cast(:d as date) end)
                returning transaction_id
                """)
            .param("d", date)
            .param("l", lifecycle)
            .param("r", templateId)
            .query(Long.class)
            .single();
    long cashLeg = posting(txnId, cash, "-9.99");
    posting(txnId, food, "9.99");
    long tag =
        jdbcClient
            .sql("insert into tag (name) values (:n) returning tag_id")
            .param("n", "Tag" + txnId)
            .query(Long.class)
            .single();
    jdbcClient
        .sql("insert into posting_tag (posting_id, tag_id) values (:p, :t)")
        .param("p", cashLeg)
        .param("t", tag)
        .update();
    return txnId;
  }

  private long posting(long txnId, long accountId, String amount) {
    return jdbcClient
        .sql(
            "insert into posting (transaction_id, account_id, amount) values (:t, :a, :amt)"
                + " returning posting_id")
        .param("t", txnId)
        .param("a", accountId)
        .param("amt", new BigDecimal(amount))
        .query(Long.class)
        .single();
  }

  private long count(String sql, long txnId) {
    return jdbcClient.sql(sql).param("t", txnId).query(Long.class).single();
  }

  private boolean gone(long txnId) {
    return count("select count(*) from transaction where transaction_id = :t", txnId) == 0
        && count("select count(*) from posting where transaction_id = :t", txnId) == 0
        && count(
                "select count(*) from posting_tag pt join posting p on pt.posting_id = p.posting_id"
                    + " where p.transaction_id = :t",
                txnId)
            == 0;
  }

  @Test
  void pendingOccurrencesAreTheTemplatesLivePendingRowsInDateOrder() {
    occurrence(template, JUN_1, CONFIRMED);
    transactionRepository.softDelete(occurrence(template, SEP_1, PENDING));
    occurrence(insertTemplate(), JUL_1, PENDING);
    occurrence(null, JUL_1, PENDING);
    long later = occurrence(template, AUG_1, PENDING);
    long earlier = occurrence(template, JUL_1, PENDING);

    assertThat(transactionRepository.findPendingOccurrences(template))
        .containsExactly(
            new PendingOccurrence(earlier, JUL_1), new PendingOccurrence(later, AUG_1));
  }

  @Test
  void occurrenceDatesAreEveryStampedDateWhateverItsLifecycle() {
    occurrence(template, JUN_1, CONFIRMED);
    occurrence(template, JUL_1, PENDING);
    transactionRepository.softDelete(occurrence(template, AUG_1, CONFIRMED));
    occurrence(insertTemplate(), SEP_1, PENDING);

    assertThat(transactionRepository.findOccurrenceDates(template))
        .containsExactlyInAnyOrder(JUN_1, JUL_1, AUG_1);
  }

  @Test
  void deletePendingOccurrenceRemovesTheTransactionItsPostingsAndTheirTags() {
    long pending = occurrence(template, JUL_1, PENDING);
    long kept = occurrence(template, AUG_1, PENDING);

    assertThat(transactionRepository.deletePendingOccurrence(pending)).isEqualTo(1);

    assertThat(gone(pending)).isTrue();
    assertThat(gone(kept)).isFalse();
  }

  @Test
  void deletePendingOccurrenceRefusesConfirmedVoidedAndUnstampedRows() {
    long confirmed = occurrence(template, JUN_1, CONFIRMED);
    long voided = occurrence(template, JUL_1, PENDING);
    transactionRepository.softDelete(voided);
    long unstamped = occurrence(null, AUG_1, PENDING);

    assertThat(transactionRepository.deletePendingOccurrence(confirmed)).isZero();
    assertThat(transactionRepository.deletePendingOccurrence(voided)).isZero();
    assertThat(transactionRepository.deletePendingOccurrence(unstamped)).isZero();
    assertThat(gone(confirmed) || gone(voided) || gone(unstamped)).isFalse();
  }
}
