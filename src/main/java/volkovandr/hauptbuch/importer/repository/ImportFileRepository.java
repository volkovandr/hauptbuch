package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportFile;

/**
 * Native-SQL access to {@code import_file} (import.md §11; plan b3). Plain inserts, filtered
 * selects and deletes by id or by (session, filename) — row-mapping round-trips for the integration
 * tier (CLAUDE.md §6). Deleting a row cascades to its {@code import_transaction} / {@code
 * import_posting} children (V19).
 */
@Repository
public class ImportFileRepository {

  private static final String SESSION_ID = "sessionId";
  private static final String FILENAME = "filename";

  /**
   * The {@code filename} column is read as {@code source_filename} so the record component matches
   * how {@code PendingImportUpload} / {@code ImportUploadView} already name it.
   */
  private static final String COLUMNS =
      "import_file_id, import_session_id, filename as source_filename, money_account_name,"
          + " charset, date_order, proposed_account_type, transaction_count, staged_at";

  private final JdbcClient jdbcClient;

  ImportFileRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Stage one confirmed file and return the persisted row (the {@code insert(record)} idiom, e.g.
   * {@code AccountRepository.insert}). {@code importFileId} and {@code stagedAt} on the draft are
   * ignored — the DB generates both.
   */
  public ImportFile insert(ImportFile file) {
    return jdbcClient
        .sql(
            """
            insert into import_file
              (import_session_id, filename, money_account_name, charset, date_order,
               proposed_account_type, transaction_count)
            values
              (:sessionId, :filename, :moneyAccountName, :charset, :dateOrder,
               :proposedAccountType, :transactionCount)
            returning import_file_id, import_session_id, filename as source_filename,
                      money_account_name, charset, date_order, proposed_account_type,
                      transaction_count, staged_at
            """)
        .param(SESSION_ID, file.importSessionId())
        .param(FILENAME, file.sourceFilename())
        .param("moneyAccountName", file.moneyAccountName())
        .param("charset", file.charset())
        .param("dateOrder", file.dateOrder())
        .param("proposedAccountType", file.proposedAccountType())
        .param("transactionCount", file.transactionCount())
        .query(ImportFile.class)
        .single();
  }

  /** The staged files of a session, oldest first — the files list on the campaign screen. */
  public List<ImportFile> findBySession(long importSessionId) {
    return jdbcClient
        .sql(
            "select "
                + COLUMNS
                + " from import_file where import_session_id = :sessionId"
                + " order by import_file_id")
        .param(SESSION_ID, importSessionId)
        .query(ImportFile.class)
        .list();
  }

  /** A staged file by id, whatever session; empty when absent. */
  public Optional<ImportFile> findById(long importFileId) {
    return jdbcClient
        .sql("select " + COLUMNS + " from import_file where import_file_id = :id")
        .param("id", importFileId)
        .query(ImportFile.class)
        .optional();
  }

  /** Whether this session already has a staged file of the given name (§2 clash check). */
  public boolean existsBySessionAndFilename(long importSessionId, String filename) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "select exists(select 1 from import_file where import_session_id = :sessionId"
                    + " and filename = :filename)")
            .param(SESSION_ID, importSessionId)
            .param(FILENAME, filename)
            .query(Boolean.class)
            .single());
  }

  /** Remove one staged file (its transactions and postings cascade). Rows affected. */
  public int deleteById(long importFileId) {
    return jdbcClient
        .sql("delete from import_file where import_file_id = :id")
        .param("id", importFileId)
        .update();
  }

  /**
   * Remove every staged file of the given name in a session — the "replace" half of the §2 clash
   * resolution. Rows affected.
   */
  public int deleteBySessionAndFilename(long importSessionId, String filename) {
    return jdbcClient
        .sql(
            "delete from import_file where import_session_id = :sessionId"
                + " and filename = :filename")
        .param(SESSION_ID, importSessionId)
        .param(FILENAME, filename)
        .update();
  }
}
