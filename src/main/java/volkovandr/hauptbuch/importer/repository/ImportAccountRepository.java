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
   * Clear {@code expect-file} for every row of a session whose Money account name already has a
   * staged file (import.md §5.1; plan e4) — the "clear every account" ergonomics flagged out of
   * scope at plan c2: toggling the flag one row at a time does not scale once dozens of files have
   * been staged. A row whose export genuinely has not arrived yet (no matching {@code import_file})
   * is left untouched — this never accepts a transfer's pending mirror ahead of its own file
   * arriving. Simple {@code exists} join against one other table — round-trip tier (CLAUDE.md §6).
   *
   * @return how many rows were cleared
   */
  public int clearExpectFileForProvidedFiles(long importSessionId) {
    return jdbcClient
        .sql(
            """
            update import_account a
               set expect_file = false
             where a.import_session_id = :sessionId
               and a.expect_file
               and exists (
                 select 1 from import_file f
                  where f.import_session_id = a.import_session_id
                    and f.money_account_name = a.money_account_name
               )
            """)
        .param(SESSION_ID, importSessionId)
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

  /**
   * The account map rows of a session still <strong>referenced</strong> by a live staged row
   * (import.md §9; plan e4) — either as a file's own account ({@code
   * import_file.money_account_name}) or as some posting's transfer-counterparty target ({@code
   * import_posting.money_account_name}). A map row persists across a file removal or replacement
   * (§5, §2), so removing the file that introduced a name can leave it behind — an <strong>orphan
   * </strong> {@link #findBySession} still returns but the commit gate must not demand a mapping
   * for (plan e4's "orphan map rows"). SQL-resident logic (three tables, an {@code exists} join),
   * covered in the {@code sqlLogicTest} tier (CLAUDE.md §6).
   */
  public List<ImportAccount> findReferencedBySession(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select a.* from import_account a
             where a.import_session_id = :sessionId
               and (
                 exists (
                   select 1 from import_file f
                    where f.import_session_id = a.import_session_id
                      and f.money_account_name = a.money_account_name
                 )
                 or exists (
                   select 1 from import_posting p
                   join import_transaction t on t.import_transaction_id = p.import_transaction_id
                   join import_file f on f.import_file_id = t.import_file_id
                   where f.import_session_id = a.import_session_id
                     and p.money_account_name = a.money_account_name
                 )
               )
             order by a.money_account_name
            """)
        .param(SESSION_ID, importSessionId)
        .query(ImportAccount.class)
        .list();
  }
}
