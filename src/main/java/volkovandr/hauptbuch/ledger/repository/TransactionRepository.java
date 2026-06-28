package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.Posting;
import volkovandr.hauptbuch.ledger.Transaction;

/**
 * Native-SQL persistence for transactions and their postings (data-model §3.5/§3.6). The two are
 * tightly coupled — a transaction is meaningless without its balanced legs — so a single repository
 * owns both, and {@link LedgerService} drives them within one transaction.
 *
 * <p>Soft delete (data-model §3.5): voiding a transaction stamps {@code deleted_at} on it;
 * integrity checks and reads scope to {@code deleted_at is null}. Postings have no {@code
 * deleted_at} of their own — a posting is live iff its transaction is, so liveness is determined by
 * the join.
 */
@Repository
public class TransactionRepository {

  private static final String TRANSACTION_ID = "transactionId";

  private final JdbcClient jdbcClient;

  TransactionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert the transaction row and return its generated id. */
  public long insertTransaction(Transaction transaction) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcClient
        .sql(
            """
            insert into transaction (date, payee_id, note, lifecycle)
            values (:date, :payeeId, :note, :lifecycle)
            """)
        .param("date", transaction.date())
        .param("payeeId", transaction.payeeId())
        .param("note", transaction.note())
        .param("lifecycle", transaction.lifecycle())
        .update(keyHolder, "transaction_id");
    return requireKey(keyHolder);
  }

  /** Insert one posting leg and return its generated id. */
  public long insertPosting(Posting posting) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcClient
        .sql(
            """
            insert into posting
              (transaction_id, account_id, amount, base_amount, reconciliation, note)
            values
              (:transactionId, :accountId, :amount, :baseAmount, :reconciliation, :note)
            """)
        .param(TRANSACTION_ID, posting.transactionId())
        .param("accountId", posting.accountId())
        .param("amount", posting.amount())
        .param("baseAmount", posting.baseAmount())
        .param("reconciliation", posting.reconciliation())
        .param("note", posting.note())
        .update(keyHolder, "posting_id");
    return requireKey(keyHolder);
  }

  /** Find a transaction by id (live or soft-deleted). */
  public Optional<Transaction> findById(long transactionId) {
    return jdbcClient
        .sql(
            """
            select transaction_id, date, payee_id, note, lifecycle,
                   created_at, updated_at, deleted_at
            from transaction
            where transaction_id = :transactionId
            """)
        .param(TRANSACTION_ID, transactionId)
        .query(Transaction.class)
        .optional();
  }

  /** The postings of a transaction, regardless of the transaction's live/deleted state. */
  public List<Posting> findPostings(long transactionId) {
    return jdbcClient
        .sql(
            """
            select posting_id, transaction_id, account_id, amount, base_amount,
                   reconciliation, note
            from posting
            where transaction_id = :transactionId
            order by posting_id
            """)
        .param(TRANSACTION_ID, transactionId)
        .query(Posting.class)
        .list();
  }

  /** Soft-delete a transaction (and, by the join, its postings). Returns rows affected. */
  public int softDelete(long transactionId) {
    return jdbcClient
        .sql(
            """
            update transaction
            set deleted_at = now(), updated_at = now()
            where transaction_id = :transactionId and deleted_at is null
            """)
        .param(TRANSACTION_ID, transactionId)
        .update();
  }

  /** Hard-delete the postings of a transaction (used when re-threading on edit). */
  public void deletePostings(long transactionId) {
    jdbcClient
        .sql("delete from posting where transaction_id = :transactionId")
        .param(TRANSACTION_ID, transactionId)
        .update();
  }

  /** Update a transaction's header fields (date, payee, note, lifecycle) on edit. */
  public void updateHeader(Transaction transaction) {
    jdbcClient
        .sql(
            """
            update transaction
            set date = :date, payee_id = :payeeId, note = :note,
                lifecycle = :lifecycle, updated_at = now()
            where transaction_id = :transactionId
            """)
        .param(TRANSACTION_ID, transaction.transactionId())
        .param("date", transaction.date())
        .param("payeeId", transaction.payeeId())
        .param("note", transaction.note())
        .param("lifecycle", transaction.lifecycle())
        .update();
  }

  private static long requireKey(KeyHolder keyHolder) {
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("Insert did not return a generated key");
    }
    return key.longValue();
  }
}
