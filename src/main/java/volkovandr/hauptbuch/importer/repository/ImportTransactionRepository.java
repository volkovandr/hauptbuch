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
}
