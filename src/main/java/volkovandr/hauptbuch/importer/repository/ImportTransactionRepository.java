package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportTransaction;

/**
 * Native-SQL access to {@code import_transaction} (import.md §11; plan b3). A plain insert (the
 * {@code insert(record)} idiom, e.g. {@code AccountRepository.insert}) and a by-file select —
 * row-mapping round-trips for the integration tier (CLAUDE.md §6). Rows are removed only by cascade
 * when their {@code import_file} is deleted.
 */
@Repository
public class ImportTransactionRepository {

  private static final String FILE_ID = "fileId";

  private final JdbcClient jdbcClient;

  ImportTransactionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Stage one transaction and return its generated id (its legs are inserted separately). {@code
   * importTransactionId}, {@code state} (DB default {@code ready}) and {@code transactionId} on the
   * draft are ignored — nothing books to the ledger until the commit.
   */
  public long insert(ImportTransaction transaction) {
    return jdbcClient
        .sql(
            """
            insert into import_transaction
              (import_file_id, date, payee_text, payee_destroyed, note, reference_number,
               cleared_status, opening_balance)
            values
              (:fileId, :date, :payeeText, :payeeDestroyed, :note, :referenceNumber,
               :clearedStatus, :openingBalance)
            returning import_transaction_id
            """)
        .param(FILE_ID, transaction.importFileId())
        .param("date", transaction.date())
        .param("payeeText", transaction.payeeText())
        .param("payeeDestroyed", transaction.payeeDestroyed())
        .param("note", transaction.note())
        .param("referenceNumber", transaction.referenceNumber())
        .param("clearedStatus", transaction.clearedStatus())
        .param("openingBalance", transaction.openingBalance())
        .query(Long.class)
        .single();
  }

  /** The staged transactions of a file, in date then id order. */
  public List<ImportTransaction> findByFile(long importFileId) {
    return jdbcClient
        .sql(
            "select * from import_transaction where import_file_id = :fileId"
                + " order by date, import_transaction_id")
        .param(FILE_ID, importFileId)
        .query(ImportTransaction.class)
        .list();
  }

  /**
   * Every staged transaction of a session that will book at the commit (plan f2): {@code state =
   * 'ready'} and not yet booked ({@code transaction_id is null}). {@code mirrored} / {@code
   * excluded} sightings (§6) and still-{@code parked} transfers are left out by the state filter —
   * the gate has already refused a commit while any park remains. Opening-balance rows are included
   * here (they are {@code ready}); the commit routes them through the reconciliation, not the
   * ordinary booking path (import.md §5.1). Date then id order, so the ledger receives the history
   * roughly chronologically.
   */
  public List<ImportTransaction> findCommittableBySession(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select t.* from import_transaction t
            join import_file f on f.import_file_id = t.import_file_id
            where f.import_session_id = :sessionId
              and t.state = 'ready'
              and t.transaction_id is null
            order by t.date, t.import_transaction_id
            """)
        .param("sessionId", importSessionId)
        .query(ImportTransaction.class)
        .list();
  }

  /**
   * How many ordinary transactions a commit would book — {@link #findCommittableBySession} minus
   * the opening-balance rows (they go through the reconciliation, not the booking loop). The
   * worker's progress total, a cheap count rather than the full staged load.
   */
  public int countCommittableBySession(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select count(*) from import_transaction t
            join import_file f on f.import_file_id = t.import_file_id
            where f.import_session_id = :sessionId
              and t.state = 'ready'
              and t.transaction_id is null
              and not t.opening_balance
            """)
        .param("sessionId", importSessionId)
        .query(Integer.class)
        .single();
  }
}
