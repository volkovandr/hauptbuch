package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportAccount;

/**
 * Native-SQL access to {@code import_account} — the account map (import.md §5.1), accumulated
 * across every file in the campaign. Plan b3 folds in <strong>unmapped</strong> rows; slice c1
 * resolves each one to a Hauptbuch account with {@link #mapToAccount}. {@code upsertUnmapped} is
 * idempotent on the {@code (session, name)} unique key so re-staging a file never duplicates a map
 * row. Row-mapping round-trips for the integration tier (CLAUDE.md §6).
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

  /**
   * Point a map row at a Hauptbuch account (import.md §5.1; plan c1) — an existing account or one
   * just created for the import. Clears any person target ({@code account_id} and {@code person_id}
   * are mutually exclusive, V19) and records the currency chosen for a new account / person leaf
   * ({@code null} for an existing account, which brings its own currency). The map is many-to-one,
   * so nothing here stops several Money names from sharing one {@code accountId}.
   */
  public void mapToAccount(long importAccountId, long accountId, String targetCurrencyCode) {
    jdbcClient
        .sql(
            """
            update import_account
               set account_id = :accountId,
                   person_id = null,
                   target_currency_code = :currency
             where import_account_id = :id
            """)
        .param("accountId", accountId)
        .param("currency", targetCurrencyCode)
        .param("id", importAccountId)
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
