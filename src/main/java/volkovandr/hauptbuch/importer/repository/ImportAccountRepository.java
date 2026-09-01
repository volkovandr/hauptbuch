package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportAccount;

/**
 * Native-SQL access to {@code import_account} — the account map (import.md §5.1), accumulated
 * across every file in the campaign. Plan b3 only folds in <strong>unmapped</strong> rows; slice c
 * resolves the target. {@code upsertUnmapped} is idempotent on the {@code (session, name)} unique
 * key so re-staging a file never duplicates a map row. Row-mapping round-trips for the integration
 * tier (CLAUDE.md §6).
 */
@Repository
public class ImportAccountRepository {

  private static final String SESSION_ID = "sessionId";

  private final JdbcClient jdbcClient;

  ImportAccountRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Record a referenced Money account name as an unmapped map row; a no-op if it is already there.
   */
  public void upsertUnmapped(long importSessionId, String moneyAccountName) {
    jdbcClient
        .sql(
            """
            insert into import_account (import_session_id, money_account_name)
            values (:sessionId, :moneyAccountName)
            on conflict (import_session_id, money_account_name) do nothing
            """)
        .param(SESSION_ID, importSessionId)
        .param("moneyAccountName", moneyAccountName)
        .update();
  }

  /** The account map of a session, by Money account name. */
  public List<ImportAccount> findBySession(long importSessionId) {
    return jdbcClient
        .sql(
            "select * from import_account where import_session_id = :sessionId"
                + " order by money_account_name")
        .param(SESSION_ID, importSessionId)
        .query(ImportAccount.class)
        .list();
  }
}
