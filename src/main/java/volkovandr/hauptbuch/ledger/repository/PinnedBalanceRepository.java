package volkovandr.hauptbuch.ledger.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL read for the landing page's Balances panel (CONTEXT.md "Balances panel", issue
 * landing-page/01): every pinned account with its all-time native balance, in one grouped query.
 *
 * <p>Joins {@code account} (the {@code accounts} module's table) to {@code posting}/{@code
 * transaction} — the same cross-table read the register does ({@code RegisterRepository}) and the
 * per-person balance does ({@code debts}' {@code AccountOwnerRepository}). The panel-visibility
 * filter (pinned, not closed, not deleted) lives here rather than in a separate {@code accounts}
 * read so the whole panel is a single query.
 */
@Repository
public class PinnedBalanceRepository {

  private final JdbcClient jdbcClient;

  PinnedBalanceRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The pinned, live, open accounts and their balances, alphabetical by name. A pinned account with
   * no live posting still appears, with a zero balance. Balance is Σ of every live posting ({@code
   * transaction.deleted_at is null}) over the account's whole history — no date filter, so
   * future-dated and opening-balance postings both count (data-model §9 report date is today for
   * the bracketed valuation, but the native balance itself ignores posting dates).
   */
  public List<PinnedBalance> findPinnedBalances() {
    return jdbcClient
        .sql(
            """
            select a.account_id,
                   a.name,
                   a.currency_code,
                   a.hue,
                   coalesce(sum(p.amount) filter (where t.deleted_at is null), 0) as balance
            from account a
            left join posting p on p.account_id = a.account_id
            left join transaction t on t.transaction_id = p.transaction_id
            where a.show_on_main_page = true
              and a.closed_at is null
              and a.deleted_at is null
            group by a.account_id, a.name, a.currency_code, a.hue
            order by a.name, a.account_id
            """)
        .query(PinnedBalance.class)
        .list();
  }
}
