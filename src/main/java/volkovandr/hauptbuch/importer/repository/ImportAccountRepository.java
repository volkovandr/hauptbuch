package volkovandr.hauptbuch.importer.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportAccount;

/**
 * Native-SQL access to {@code import_account} — the account map (import.md §5.1, §5.4), accumulated
 * across every file in the campaign. Plan b3 folds in <strong>unmapped</strong> rows; slice c
 * resolves each one to a Hauptbuch account with {@link #mapToAccount} (a person target, plan c2,
 * resolves to that person's leaf and lands here as an ordinary account id) and toggles {@link
 * #setExpectFile}. {@code upsertUnmapped} is idempotent on the {@code (session, name)} unique key
 * so re-staging a file never duplicates a map row. Row-mapping round-trips for the integration tier
 * (CLAUDE.md §6).
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
   * Point a map row at a Hauptbuch account (import.md §5.1, §5.4; plan c1/c2) — an existing
   * account, one just created for the import, or a person's per-currency leaf (§5.4, resolved by
   * the service). Records the currency chosen for a new account / person leaf ({@code null} for an
   * existing account, which brings its own currency). The map is many-to-one, so nothing here stops
   * several Money names from sharing one {@code accountId}.
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

  /**
   * Set the {@code expect-file} flag on one map row (import.md §5.1, §6.4; plan c2) — "am I still
   * waiting for this account's own export?". The commit gate stays locked while any row is still
   * {@code true} (§9); clearing a counterparty account's flag accepts its pending mirrors as the
   * one file states them (§6.4).
   */
  public void setExpectFile(long importAccountId, boolean expectFile) {
    jdbcClient
        .sql("update import_account set expect_file = :expectFile where import_account_id = :id")
        .param("expectFile", expectFile)
        .param("id", importAccountId)
        .update();
  }

  /**
   * Record the opening-balance reconciliation outcome on one map row (import.md §5.1; plan c3):
   * {@code keep_hauptbuch} / {@code take_money} / {@code override}. {@code amount} is the explicit
   * figure for an {@code override} and {@code null} otherwise. The actual voiding / booking happens
   * at commit (f2) — this only records the decision.
   */
  public void setOpeningBalanceChoice(long importAccountId, String choice, BigDecimal amount) {
    jdbcClient
        .sql(
            """
            update import_account
               set opening_balance_choice = :choice,
                   opening_balance_amount = :amount
             where import_account_id = :id
            """)
        .param("choice", choice)
        .param("amount", amount)
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
