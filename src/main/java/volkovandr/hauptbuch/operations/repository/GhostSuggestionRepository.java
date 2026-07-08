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
 * multi-table join), so it lives here rather than being reconstructed in Java. It rolls a
 * per-currency leaf up to its <em>semantic</em> category (the parent — data-model §6.5) so {@code
 * Food EUR} and {@code Food CHF} count as one {@code Food}, which is what the user actually picks.
 * It lives in {@code operations} beside the dock's commit path (plan stage 7 boundary note).
 */
@Repository
public class GhostSuggestionRepository {

  private final JdbcClient jdbcClient;

  GhostSuggestionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The single most-common category for a payee's live transactions, or empty if the payee has
   * none. A counterpart leg is a category (income/expense) leg; each is rolled up to its semantic
   * category — its parent when that parent is itself a category (the per-currency-leaf case, §6.5),
   * else the leaf itself. Categories are ranked by how many of the payee's transactions used them,
   * ties broken by the most recent transaction date (register §3.9, Q-UI-3).
   *
   * @param payeeId the accepted payee
   */
  public Optional<GhostSuggestion> suggestFor(long payeeId) {
    return jdbcClient
        .sql(
            """
            with category_legs as (
              select
                -- Roll a per-currency leaf up to its semantic category: the parent when the parent
                -- is itself a category, otherwise the leaf itself (§6.5).
                case when parent.type in ('income', 'expense')
                     then parent.account_id else leaf.account_id end as category_id,
                case when parent.type in ('income', 'expense')
                     then parent.name else leaf.name end as category_name,
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
        .param("payeeId", payeeId)
        .query(GhostSuggestion.class)
        .optional();
  }
}
