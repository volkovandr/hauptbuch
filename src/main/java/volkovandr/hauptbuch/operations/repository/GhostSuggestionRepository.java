package volkovandr.hauptbuch.operations.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.operations.GhostSuggestion;

/**
 * Native-SQL for the dock's ghost category suggestion (register §3.9, plan stage 7b) — the
 * most-common category a payee's past transactions used, ties broken by most recent use (Q-UI-3:
 * plain statistical mode, recency as the tie-break).
 *
 * <p>This is SQL-resident logic (grouping, per-payee aggregation, a two-key tie-break over a
 * multi-table join), so it lives here rather than being reconstructed in Java. It rolls an
 * auto-managed currency leaf up to its <em>semantic</em> category (the parent — data-model §6.5) so
 * a {@code Food} spend in EUR and one in CHF count as one {@code Food}, which is what the user
 * actually picks. It lives in {@code operations} beside the dock's commit path (plan stage 7
 * boundary note).
 */
@Repository
public class GhostSuggestionRepository {

  private static final String PAYEE_ID = "payeeId";
  private static final String ACCOUNT_ID = "accountId";

  private final JdbcClient jdbcClient;

  GhostSuggestionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The single most-common category for a payee's live transactions, or empty if the payee has
   * none. A counterpart leg is a category (income/expense) leg; each is rolled up to its semantic
   * category — its parent when the leg is an auto-managed currency leaf (data-model §6.5), else the
   * leaf itself. Categories are ranked by how many of the payee's transactions used them, ties
   * broken by the most recent transaction date (register §3.9, Q-UI-3).
   *
   * @param payeeId the accepted payee
   */
  public Optional<GhostSuggestion> suggestFor(long payeeId) {
    return jdbcClient
        .sql(
            """
            with category_legs as (
              select
                -- Roll an auto-managed currency leaf up to its semantic category (§6.5); a real
                -- sub-category (e.g. "Sweets" under "Food") stays itself.
                case when leaf.currency_leaf then parent.account_id else leaf.account_id end
                  as category_id,
                case when leaf.currency_leaf then parent.name else leaf.name end as category_name,
                t.date
              from transaction t
              join posting p on p.transaction_id = t.transaction_id
              join account leaf on p.account_id = leaf.account_id
              left join account parent on leaf.parent_id = parent.account_id
              where t.payee_id = :payeeId
                and t.deleted_at is null
                and leaf.type in ('income', 'expense')
            )
            select category_id, category_name
            from category_legs
            group by category_id, category_name
            order by count(*) desc, max(date) desc, category_name
            limit 1
            """)
        .param(PAYEE_ID, payeeId)
        .query(GhostSuggestion.class)
        .optional();
  }

  /**
   * The transaction currency of the most recent live transaction this payee had on this account
   * (issue transaction-register-ui/17), or empty if the pair has no history. The transaction
   * currency is the currency of the legs that are <em>not</em> in the account's own currency — a
   * EUR card paying a USD bill was a USD transaction — and the account's own currency when every
   * leg shares it. A transaction spans at most two native currencies (register §3.8a), so at most
   * one other currency can be present; ties on date go to the later-entered transaction.
   *
   * @param payeeId the accepted payee
   * @param accountId the funding account
   */
  public Optional<String> suggestCurrencyFor(long payeeId, long accountId) {
    return jdbcClient
        .sql(
            """
            with latest as (
              select t.transaction_id
              from transaction t
              join posting p on p.transaction_id = t.transaction_id
              where t.payee_id = :payeeId
                and p.account_id = :accountId
                and t.deleted_at is null
              order by t.date desc, t.transaction_id desc
              limit 1
            )
            select coalesce(max(other.currency_code), funding.currency_code) as currency_code
            from latest
            join account funding on funding.account_id = :accountId
            left join posting p on p.transaction_id = latest.transaction_id
            left join account other
              on other.account_id = p.account_id
              and other.currency_code <> funding.currency_code
            group by funding.currency_code
            """)
        .param(PAYEE_ID, payeeId)
        .param(ACCOUNT_ID, accountId)
        .query(String.class)
        .optional();
  }
}
