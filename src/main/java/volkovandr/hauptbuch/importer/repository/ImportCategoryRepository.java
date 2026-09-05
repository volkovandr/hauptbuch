package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportCategory;

/**
 * Native-SQL access to {@code import_category} — the category map (import.md §5.2), keyed by the
 * full Money path and accumulated across every file in the campaign. Plan b3 only folds in
 * <strong>unmapped</strong> rows; slice d resolves the semantic category, its tags and the sign
 * evidence. {@code upsertUnmapped} is idempotent on the {@code (session, path)} unique key so
 * re-staging a file never duplicates a map row. Row-mapping round-trips for the integration tier
 * (CLAUDE.md §6).
 */
@Repository
public class ImportCategoryRepository {

  private static final String SESSION_ID = "sessionId";

  private final JdbcClient jdbcClient;

  ImportCategoryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Record a Money category path as an unmapped map row; a no-op if it is already there. */
  public void upsertUnmapped(long importSessionId, String moneyPath) {
    jdbcClient
        .sql(
            """
            insert into import_category (import_session_id, money_path)
            values (:sessionId, :moneyPath)
            on conflict (import_session_id, money_path) do nothing
            """)
        .param(SESSION_ID, importSessionId)
        .param("moneyPath", moneyPath)
        .update();
  }

  /**
   * Point a map row at a Hauptbuch category (import.md §5.2; plan d1) — always a semantic posting
   * leaf, never a currency leaf (the service validates that with {@code categories}). The map is
   * many-to-one: several Money paths may consolidate onto one category, so nothing here stops a
   * repeated {@code accountId}.
   */
  public void mapToCategory(long importCategoryId, long accountId) {
    jdbcClient
        .sql("update import_category set account_id = :accountId where import_category_id = :id")
        .param("accountId", accountId)
        .param("id", importCategoryId)
        .update();
  }

  /** The category map of a session, by Money path. */
  public List<ImportCategory> findBySession(long importSessionId) {
    return jdbcClient
        .sql(
            "select * from import_category where import_session_id = :sessionId"
                + " order by money_path")
        .param(SESSION_ID, importSessionId)
        .query(ImportCategory.class)
        .list();
  }

  /**
   * The category map rows of a session still <strong>referenced</strong> by a live staged posting
   * (import.md §9; plan e4) — the category-side counterpart of {@link
   * ImportAccountRepository#findReferencedBySession}. A map row persists across a file removal
   * (§5), so removing the last file that named a path can leave it behind — an <strong>orphan
   * </strong> {@link #findBySession} still returns but the commit gate must not demand a mapping
   * for. SQL-resident logic (three tables, an {@code exists} join), covered in the {@code
   * sqlLogicTest} tier (CLAUDE.md §6).
   */
  public List<ImportCategory> findReferencedBySession(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select c.* from import_category c
             where c.import_session_id = :sessionId
               and exists (
                 select 1 from import_posting p
                 join import_transaction t on t.import_transaction_id = p.import_transaction_id
                 join import_file f on f.import_file_id = t.import_file_id
                 where f.import_session_id = c.import_session_id
                   and p.money_category_path = c.money_path
               )
             order by c.money_path
            """)
        .param(SESSION_ID, importSessionId)
        .query(ImportCategory.class)
        .list();
  }
}
