package volkovandr.hauptbuch.analytics.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access for the report engine (reporting.md §14): grouped reads of {@code posting} /
 * {@code transaction} / {@code account} / {@code tag}, dimension-agnostic per {@link
 * RawTurnoverCell} — the query always returns both the grouped dimension value and the month
 * bucket, and {@code ReportEngine} decides which axis each lands on.
 *
 * <p>Reads the {@code account} and {@code tag} tables directly, the same cross-module table access
 * {@code ledger}'s {@code PinnedBalanceRepository} and {@code RegisterRepository} already use — the
 * Modulith boundary is on Java types, not on shared storage.
 *
 * <p><strong>Wired in this slice:</strong> the account tree (serves both {@link
 * volkovandr.hauptbuch.analytics.Dimension#CATEGORY} and {@link
 * volkovandr.hauptbuch.analytics.Dimension#ACCOUNT} — they differ only in which {@code
 * account.type}s the caller scopes to), the tag tree, and the no-dimension total. {@link
 * volkovandr.hauptbuch.analytics.Dimension#PAYEE}, {@code PERSON}, {@code CURRENCY} and {@code
 * ACCOUNT_TYPE} ship in a follow-up within stage a; {@code ReportEngine} rejects them explicitly
 * rather than silently mis-grouping.
 */
@Repository
public class ReportQueryRepository {

  private static final String TYPES = "types";
  private static final String START_DATE = "startDate";
  private static final String END_DATE = "endDate";
  private static final String BASE_CURRENCY = "baseCurrency";
  private static final String LEG = "leg";
  private static final String INCLUDE_CLOSED = "includeClosedAccounts";
  private static final String INCLUDE_PENDING_REVIEW = "includePendingReview";
  private static final String AS_OF = "asOf";

  /**
   * Every account's top-level ancestor (data-model §5's hierarchy walked to the root, not just one
   * level) — shared by every account-tree query so row grouping and labelling cannot drift apart.
   */
  private static final String ACCOUNT_ANCESTOR_CTE =
      """
      with recursive account_ancestor(account_id, top_id) as (
        select account_id, account_id from account where parent_id is null
        union all
        select a.account_id, anc.top_id
        from account a
        join account_ancestor anc on a.parent_id = anc.account_id
      )
      """;

  /**
   * Every tag's top-level ancestor, mirroring {@link #ACCOUNT_ANCESTOR_CTE} for the tag tree
   * (data-model §10.3).
   */
  private static final String TAG_ANCESTOR_CTE =
      """
      with recursive tag_ancestor(tag_id, top_id) as (
        select tag_id, tag_id from tag where parent_id is null and deleted_at is null
        union all
        select tg.tag_id, anc.top_id
        from tag tg
        join tag_ancestor anc on tg.parent_id = anc.tag_id
        where tg.deleted_at is null
      )
      """;

  /**
   * The rate-as-of lookup (data-model §3.7) joined once per posting so both the base sum and the
   * missing-rate count read the same resolved rate.
   */
  private static final String RATE_LATERAL_JOIN =
      """
      left join lateral (
        select er.rate
        from exchange_rate er
        where er.currency_code = a.currency_code and er.date <= t.date
        order by er.date desc
        limit 1
      ) rate on a.currency_code <> :baseCurrency
      """;

  private static final String BASE_AMOUNT_EXPR =
      """
      coalesce(p.base_amount,
        case when a.currency_code = :baseCurrency then p.amount else p.amount * rate.rate end)
      """;

  private static final String MISSING_RATE_EXPR =
      """
      (p.base_amount is null and a.currency_code <> :baseCurrency and rate.rate is null)
      """;

  private static final String LEG_PREDICATE =
      "(:leg = 'NET' or (:leg = 'DEBITS' and p.amount > 0) or (:leg = 'CREDITS' and p.amount < 0))";

  private static final String SCOPE_PREDICATE =
      """
      a.type in (:types)
        and a.deleted_at is null
        and (:includeClosedAccounts or a.closed_at is null)
        and t.deleted_at is null
        and (:includePendingReview or t.lifecycle = 'confirmed')
        and t.date between :startDate and :endDate
      """;

  private final JdbcClient jdbcClient;

  ReportQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Turnover grouped by each posting's account's top-level ancestor and month bucket — the
   * category×month matrix's query (reporting.md §16), and equally the {@code ACCOUNT} dimension
   * when {@code types} is the asset/liability/equity set instead of income/expense.
   */
  public List<RawTurnoverCell> accountTreeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview) {
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE
                + """
                select anc.top_id::text as dimension_key,
                       top.name as dimension_label,
                       top.type as dimension_type,
                       to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                       a.currency_code as currency_code,
                       sum(p.amount) as native_amount,
                       sum("""
                + BASE_AMOUNT_EXPR
                + """
                ) as base_amount,
                       count(*) filter (where """
                + MISSING_RATE_EXPR
                + """
                ) as missing_rate_count,
                       count(*) as posting_count,
                       count(distinct p.transaction_id) as transaction_count
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                join account_ancestor anc on anc.account_id = a.account_id
                join account top on top.account_id = anc.top_id
                """
                + RATE_LATERAL_JOIN
                + "where "
                + SCOPE_PREDICATE
                + "  and "
                + LEG_PREDICATE
                + """
                group by anc.top_id, top.name, top.type, month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * Turnover grouped by each posting's top-level tag and month bucket. A posting distinctly carries
   * one row per top-level tag family it touches ({@code distinct} on posting/top-tag before the
   * join to the measured posting), so a posting tagged both {@code Car:Audi} and {@code Car:Skoda}
   * counts once under {@code Car} — never twice (data-model §10.3).
   */
  public List<RawTurnoverCell> tagTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview) {
    return jdbcClient
        .sql(
            TAG_ANCESTOR_CTE
                + """
                ,
                tag_matches as (
                  select distinct pt.posting_id, tanc.top_id
                  from posting_tag pt
                  join tag_ancestor tanc on tanc.tag_id = pt.tag_id
                )
                select tm.top_id::text as dimension_key,
                       tg.name as dimension_label,
                       cast(null as text) as dimension_type,
                       to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                       a.currency_code as currency_code,
                       sum(p.amount) as native_amount,
                       sum("""
                + BASE_AMOUNT_EXPR
                + """
                ) as base_amount,
                       count(*) filter (where """
                + MISSING_RATE_EXPR
                + """
                ) as missing_rate_count,
                       count(*) as posting_count,
                       count(distinct p.transaction_id) as transaction_count
                from tag_matches tm
                join posting p on p.posting_id = tm.posting_id
                join tag tg on tg.tag_id = tm.top_id
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + RATE_LATERAL_JOIN
                + "where "
                + SCOPE_PREDICATE
                + "  and "
                + LEG_PREDICATE
                + """
                group by tm.top_id, tg.name, month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * Turnover with no dimension grouping at all — every in-scope posting collapsed into one bucket
   * per month and currency. Used when neither report axis carries a wired dimension (§5.2 — sum is
   * always legal over both axes for a flow measure, so this is never illegal, merely coarse).
   */
  public List<RawTurnoverCell> totalTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview) {
    return jdbcClient
        .sql(
            """
            select 'total' as dimension_key,
                   'Total' as dimension_label,
                   cast(null as text) as dimension_type,
                   to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                   a.currency_code as currency_code,
                   sum(p.amount) as native_amount,
                   sum("""
                + BASE_AMOUNT_EXPR
                + """
                ) as base_amount,
                   count(*) filter (where """
                + MISSING_RATE_EXPR
                + """
                ) as missing_rate_count,
                   count(*) as posting_count,
                   count(distinct p.transaction_id) as transaction_count
            from posting p
            join transaction t on t.transaction_id = p.transaction_id
            join account a on a.account_id = p.account_id
            """
                + RATE_LATERAL_JOIN
                + "where "
                + SCOPE_PREDICATE
                + "  and "
                + LEG_PREDICATE
                + """
                group by month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * The native closing balance of each top-level account, as of {@code asOf} (data-model §6.1's
   * stock rule — the cumulative position, valued by the engine at the report date's rate). The
   * balance-sheet Preset's query (reporting.md §16).
   */
  public List<RawBalanceCell> accountTreeClosingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview) {
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE
                + """
                select anc.top_id::text as dimension_key,
                       top.name as dimension_label,
                       top.type as dimension_type,
                       a.currency_code as currency_code,
                       sum(p.amount) as native_balance
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                join account_ancestor anc on anc.account_id = a.account_id
                join account top on top.account_id = anc.top_id
                where a.type in (:types)
                  and a.deleted_at is null
                  and (:includeClosedAccounts or a.closed_at is null)
                  and t.deleted_at is null
                  and (:includePendingReview or t.lifecycle = 'confirmed')
                  and t.date <= :asOf
                group by anc.top_id, top.name, top.type, a.currency_code
                """)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * The native closing balance of every in-scope posting, collapsed into one row per currency — the
   * no-dimension counterpart to {@link #accountTreeClosingBalance} (e.g. "total net worth" with no
   * row/column dimension chosen).
   */
  public List<RawBalanceCell> totalClosingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview) {
    return jdbcClient
        .sql(
            """
            select 'total' as dimension_key,
                   'Total' as dimension_label,
                   cast(null as text) as dimension_type,
                   a.currency_code as currency_code,
                   sum(p.amount) as native_balance
            from posting p
            join transaction t on t.transaction_id = p.transaction_id
            join account a on a.account_id = p.account_id
            where a.type in (:types)
              and a.deleted_at is null
              and (:includeClosedAccounts or a.closed_at is null)
              and t.deleted_at is null
              and (:includePendingReview or t.lifecycle = 'confirmed')
              and t.date <= :asOf
            group by a.currency_code
            """)
        .param(TYPES, types)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(AS_OF, asOf)
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * Every live, non-currency-leaf top-level account of the given types — the {@code CATEGORY}/
   * {@code ACCOUNT} row candidates, including ones with no activity this period, so an all-blank
   * row can be suppressed rather than simply never listed (reporting.md §7.3).
   */
  public List<TopLevelNode> topLevelAccounts(List<String> types, boolean includeClosedAccounts) {
    return jdbcClient
        .sql(
            """
            select account_id::text as key, name as label, type
            from account
            where type in (:types)
              and parent_id is null
              and currency_leaf = false
              and deleted_at is null
              and (:includeClosedAccounts or closed_at is null)
            order by name
            """)
        .param(TYPES, types)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  /** Every live top-level tag — the {@code TAG} row candidates (see {@link #topLevelAccounts}). */
  public List<TopLevelNode> topLevelTags() {
    return jdbcClient
        .sql(
            """
            select tag_id::text as key, name as label, cast(null as text) as type
            from tag
            where parent_id is null
              and deleted_at is null
            order by name
            """)
        .query(TopLevelNode.class)
        .list();
  }
}
