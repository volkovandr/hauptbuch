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
}
