package volkovandr.hauptbuch.ledger.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.ledger.RegisterCounterpartLeg;
import volkovandr.hauptbuch.ledger.RegisterRow;

/**
 * Native-SQL access for the transaction register (register §2, plan stage 7a): the row query with
 * per-account running balances, and the counterpart-leg lookup that feeds the Category cell.
 *
 * <p>The register screen lives in {@code ledger} (the feature module owns its screen — plan stage
 * 7, boundary note), so this repository joins the {@code account} table read-only to project the
 * row's account name / hue / currency. The {@code account} <em>table</em> is shared storage; the
 * module boundary {@code verify()} enforces is on Java types, and this reaches for none of {@code
 * accounts}' internals.
 *
 * <p>The running-balance window is the query the stage-3 SQL-logic suite wrote TDD-ahead; {@link
 * #findRows} is where it lands in production (CLAUDE.md §6 — that marker closes here).
 */
@Repository
public class RegisterRepository {

  private static final String ACCOUNT_IDS = "accountIds";
  private static final String FROM_DATE = "fromDate";
  private static final String TO_DATE = "toDate";
  private static final String PAYEE_ID = "payeeId";
  private static final String BASE_CURRENCY = "baseCurrency";
  private static final String TRANSACTION_IDS = "transactionIds";

  private final JdbcClient jdbcClient;

  RegisterRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The register rows for the viewed accounts: one row per live posting to one of {@code
   * accountIds}, in {@code (date, transaction_id, posting_id)} order, each carrying its account's
   * native running balance up to and including that leg.
   *
   * <p>The running balance is a windowed sum over the account's <em>entire</em> live history
   * (computed in the {@code threaded} CTE before any display filter), so a row's "balance after"
   * stays correct as-of even when older rows fall outside {@code fromDate} or the payee filter
   * hides intervening rows. The date-range and payee filters are applied only in the outer select,
   * to what is <em>shown</em> — never to what the balance accumulates over (register §2.7).
   *
   * @param accountIds the viewed accounts; an empty list yields no rows
   * @param fromDate inclusive lower bound on the shown rows' dates; null for no lower bound
   * @param toDate inclusive upper bound on the shown rows' dates; null for no upper bound
   * @param payeeId show only rows whose transaction has this payee; null for all payees
   * @param baseCurrency the book's base currency, to flag base vs non-base rows for display
   */
  public List<RegisterRow> findRows(
      List<Long> accountIds,
      LocalDate fromDate,
      LocalDate toDate,
      Long payeeId,
      String baseCurrency) {
    if (accountIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            with threaded as (
              select p.posting_id,
                     p.transaction_id,
                     t.date,
                     a.account_id,
                     a.name          as account_name,
                     a.hue           as account_hue,
                     a.currency_code as currency_code,
                     t.payee_id,
                     t.lifecycle,
                     p.amount,
                     p.reconciliation,
                     sum(p.amount) over (
                       partition by p.account_id
                       order by t.date, p.transaction_id, p.posting_id
                       rows between unbounded preceding and current row
                     ) as running_balance
              from posting p
              join transaction t on p.transaction_id = t.transaction_id
              join account a on p.account_id = a.account_id
              where p.account_id in (:accountIds)
                and t.deleted_at is null
            )
            select threaded.posting_id,
                   threaded.transaction_id,
                   threaded.date,
                   threaded.account_id,
                   threaded.account_name,
                   threaded.account_hue,
                   threaded.currency_code,
                   (threaded.currency_code = :baseCurrency) as base_currency,
                   -- Display "Name · City · Country" so same-named payees are distinguishable
                   -- (register §3.4); the city/country parts drop out when absent.
                   pay.name
                     || coalesce(' · ' || pay.city, '')
                     || coalesce(' · ' || pay_country.name, '') as payee_name,
                   threaded.amount,
                   threaded.running_balance,
                   threaded.lifecycle,
                   threaded.reconciliation
            from threaded
            left join payee pay on threaded.payee_id = pay.payee_id
            left join country pay_country on pay.country_code = pay_country.country_code
            where (cast(:fromDate as date) is null or threaded.date >= :fromDate)
              and (cast(:toDate as date) is null or threaded.date <= :toDate)
              and (cast(:payeeId as bigint) is null or threaded.payee_id = :payeeId)
            order by threaded.date, threaded.transaction_id, threaded.posting_id
            """)
        .param(ACCOUNT_IDS, accountIds)
        .param(FROM_DATE, fromDate)
        .param(TO_DATE, toDate)
        .param(PAYEE_ID, payeeId)
        .param(BASE_CURRENCY, baseCurrency)
        .query(RegisterRow.class)
        .list();
  }

  /**
   * Every live leg of a set of transactions, biggest-magnitude first within each transaction. These
   * feed the Category cell (register §2.6): the renderer keeps, per row, the legs <em>other
   * than</em> the row's own account — an income/expense leg shows as its category name, another own
   * account as a direction-arrowed transfer target (plan stage 7d.3). Fetching all legs (rather
   * than pre-excluding the viewed accounts) is what lets a transfer between two viewed accounts
   * show the other account in each row's cell; the per-row exclusion is done in the renderer.
   *
   * <p>A leg on one of {@code CurrencyLeafService}'s auto-managed per-currency leaves (data-model
   * §6.5) rolls up to its semantic parent's id/name/type — the currency leaf itself is never shown
   * (the leg's own {@link RegisterRow} already displays the posting's actual currency, §2.9).
   *
   * @param transactionIds the transactions on screen (typically the ids of {@link #findRows})
   */
  public List<RegisterCounterpartLeg> findTransactionLegs(List<Long> transactionIds) {
    if (transactionIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            select p.transaction_id,
                   coalesce(parent.account_id, a.account_id) as account_id,
                   coalesce(parent.name, a.name) as account_name,
                   coalesce(parent.type, a.type) as account_type,
                   p.amount
            from posting p
            join account a on p.account_id = a.account_id
            left join account parent on a.currency_leaf and a.parent_id = parent.account_id
            where p.transaction_id in (:transactionIds)
            order by p.transaction_id, abs(p.amount) desc, coalesce(parent.name, a.name)
            """)
        .param(TRANSACTION_IDS, transactionIds)
        .query(RegisterCounterpartLeg.class)
        .list();
  }
}
