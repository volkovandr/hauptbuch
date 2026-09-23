package volkovandr.hauptbuch.analytics.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.analytics.FilterLevel;
import volkovandr.hauptbuch.analytics.FilterOperator;
import volkovandr.hauptbuch.analytics.ReportFilter;

/**
 * Native-SQL access for the report engine (reporting.md §14): grouped reads of {@code posting} /
 * {@code transaction} / {@code account} / {@code tag} / {@code payee} / {@code person} / {@code
 * currency}, dimension-agnostic per {@link RawTurnoverCell} — the query always returns both the
 * grouped dimension value and the month bucket, and {@code ReportEngine} decides which axis each
 * lands on.
 *
 * <p>Reads the {@code account}, {@code tag}, {@code payee}, {@code person}, {@code account_owner}
 * and {@code currency} tables directly, the same cross-module table access {@code ledger}'s {@code
 * PinnedBalanceRepository} and {@code RegisterRepository} already use — the Modulith boundary is on
 * Java types, not on shared storage.
 *
 * <p>Every turnover/closing-balance method takes a {@link QueryConstraints}: its {@link
 * QueryConstraints#filters()} (§6.2–§6.3) are compiled by {@link #compileExtra} into one extra
 * {@code and}-ed SQL fragment, referencing whichever of this query's own {@code p}(osting)/{@code
 * a}(ccount)/{@code t}(ransaction) aliases every method consistently uses — a posting-level filter
 * restricts them directly; a transaction-level filter wraps an {@code exists} over a private alias
 * so a transaction qualifies the moment <em>any</em> of its postings matches, without touching the
 * measured posting's own join (§6.2's worked example).
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
  private static final String PARENT_ID = "parentId";
  private static final String WHERE = "where ";
  private static final String AND = "  and ";
  private static final String ON = " on ";

  /**
   * Whether a candidate account has at least one live, non-leaf, expandable child of its own (stage
   * e2's expand triangle, reporting.md §9.1) — shared verbatim by {@link #topLevelAccounts} and
   * {@link #childAccountCandidates} so the child-eligibility rule cannot drift between them.
   */
  private static final String ACCOUNT_HAS_CHILDREN_EXISTS =
      """
      exists(
        select 1 from account c
        where c.parent_id = account.account_id
          and c.currency_leaf = false
          and c.person_leaf = false
          and c.deleted_at is null
          and (:includeClosedAccounts or c.closed_at is null)
      ) as has_children
      """;

  /**
   * {@link #ACCOUNT_HAS_CHILDREN_EXISTS}'s own mirror for a Tag candidate — shared by {@link
   * #topLevelTags} and {@link #childTagCandidates}.
   */
  private static final String TAG_HAS_CHILDREN_EXISTS =
      """
      exists(
        select 1 from tag c
        where c.parent_id = tag.tag_id and c.deleted_at is null
      ) as has_children
      """;

  /** See {@link #orNoMatch}. */
  private static final List<Long> NO_MATCH = List.of(-1L);

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
   * A per-person debt leaf (data-model §7) is its own root in {@link #ACCOUNT_ANCESTOR_CTE} —
   * {@code parent_id is null} like a real top-level account — so without this, the {@code
   * CATEGORY}/{@code ACCOUNT} dimension would list every person's leaf individually under its
   * cosmetic, non-owner name ({@code personal.<CUR>}, data-model §7). Collapse every leaf sharing a
   * currency into one {@code "Personal debts (<CUR>)"} bucket instead; per-person expansion is
   * deferred (reporting.md Q-REP-1) to the {@code PERSON} dimension, which already labels correctly
   * via {@link #personCandidates}.
   */
  private static final String ACCOUNT_DIMENSION_KEY =
      "case when a.person_leaf then 'personal:' || a.currency_code else anc.top_id::text end";

  private static final String ACCOUNT_DIMENSION_LABEL =
      "case when a.person_leaf then 'Personal debts (' || a.currency_code || ')' else top.name end";

  private static final String ACCOUNT_DIMENSION_TYPE =
      "case when a.person_leaf then a.type else top.type end";

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
   * {@link #ACCOUNT_ANCESTOR_CTE} generalized to an arbitrary seed instead of the root: every
   * descendant of {@code :parentId}, grouped by which of {@code :parentId}'s <em>direct</em>
   * children it rolls up to — expanding one node one level (reporting.md §9.1). No person-leaf
   * special case here (unlike {@link #ACCOUNT_ANCESTOR_CTE}): a debt leaf is always its own root
   * (data-model §7), so it can never appear as a descendant of a real category/account node.
   */
  private static final String ACCOUNT_ANCESTOR_CTE_FROM_PARENT =
      """
      with recursive account_ancestor(account_id, top_id) as (
        select account_id, account_id from account where parent_id = :parentId
        union all
        select a.account_id, anc.top_id
        from account a
        join account_ancestor anc on a.parent_id = anc.account_id
      )
      """;

  /**
   * {@link #TAG_ANCESTOR_CTE} generalized to an arbitrary seed instead of the root, mirroring
   * {@link #ACCOUNT_ANCESTOR_CTE_FROM_PARENT} for the tag tree: every descendant of {@code
   * :parentId}, grouped by which of {@code :parentId}'s direct children it rolls up to.
   */
  private static final String TAG_ANCESTOR_CTE_FROM_PARENT =
      """
      with recursive tag_ancestor(tag_id, top_id) as (
        select tag_id, tag_id from tag where parent_id = :parentId and deleted_at is null
        union all
        select tg.tag_id, anc.top_id
        from tag tg
        join tag_ancestor anc on tg.parent_id = anc.tag_id
        where tg.deleted_at is null
      )
      """;

  /**
   * A sentinel {@code top_id} for the "tagged directly on the expanded node itself" bucket (§9.3) —
   * never a real {@code tag_id} (bigserial, always positive), so it can share {@link
   * #childTagTurnover}'s grouping/labelling {@code case} with the real child rows without
   * colliding.
   */
  private static final long UNSPECIFIED_TOP_ID = -1L;

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

  private static final String TURNOVER_AGGREGATES =
      """
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
      """;

  private final JdbcClient jdbcClient;

  ReportQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // ── accountTreeTurnover / accountTreeClosingBalance (Category/Account) ─────

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
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE
                + "select "
                + ACCOUNT_DIMENSION_KEY
                + " as dimension_key,\n       "
                + ACCOUNT_DIMENSION_LABEL
                + " as dimension_label,\n       "
                + ACCOUNT_DIMENSION_TYPE
                + " as dimension_type,\n"
                + """
                       to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                       a.currency_code as currency_code,
                       """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                join account_ancestor anc on anc.account_id = a.account_id
                join account top on top.account_id = anc.top_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
                + """
                group by dimension_key, dimension_label, dimension_type, month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
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
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE
                + "select "
                + ACCOUNT_DIMENSION_KEY
                + " as dimension_key,\n       "
                + ACCOUNT_DIMENSION_LABEL
                + " as dimension_label,\n       "
                + ACCOUNT_DIMENSION_TYPE
                + " as dimension_type,\n"
                + """
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
                """
                + extra.sql()
                + """
                group by dimension_key, dimension_label, dimension_type, a.currency_code
                """)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
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
            "select account_id::text as key, name as label, type,\n       "
                + ACCOUNT_HAS_CHILDREN_EXISTS
                + """
            from account
            where type in (:types)
              and parent_id is null
              and currency_leaf = false
              and person_leaf = false
              and deleted_at is null
              and (:includeClosedAccounts or closed_at is null)
            union all
            select distinct 'personal:' || currency_code as key,
                   'Personal debts (' || currency_code || ')' as label,
                   type,
                   false as has_children
            from account
            where type in (:types)
              and person_leaf = true
              and deleted_at is null
              and (:includeClosedAccounts or closed_at is null)
            order by label
            """)
        .param(TYPES, types)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  // ── childAccountTurnover / childAccountClosingBalance (stage e nesting, §9.1) ──

  /**
   * Turnover grouped by which direct child of {@code parentId} each posting's account rolls up to
   * (reporting.md §9.1) — {@link #accountTreeTurnover} seeded at an arbitrary node instead of the
   * root, for expanding one {@code CATEGORY}/{@code ACCOUNT} row into its own children.
   */
  public List<RawTurnoverCell> childAccountTurnover(
      long parentId,
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE_FROM_PARENT
                + """
                select anc.top_id::text as dimension_key,
                       top.name as dimension_label,
                       top.type as dimension_type,
                       to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                       a.currency_code as currency_code,
                       """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                join account_ancestor anc on anc.account_id = a.account_id
                join account top on top.account_id = anc.top_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
                + """
                group by dimension_key, dimension_label, dimension_type, month_key, a.currency_code
                """)
        .param(PARENT_ID, parentId)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * {@link #accountTreeClosingBalance}, seeded at {@code parentId} — mirrors {@link
   * #childAccountTurnover}.
   */
  public List<RawBalanceCell> childAccountClosingBalance(
      long parentId,
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE_FROM_PARENT
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
                """
                + extra.sql()
                + """
                group by dimension_key, dimension_label, dimension_type, a.currency_code
                """)
        .param(PARENT_ID, parentId)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * Every live, non-currency-leaf direct child of {@code parentId} — the child-row candidates for
   * expanding a {@code CATEGORY}/{@code ACCOUNT} row one level (see {@link #topLevelAccounts}),
   * including ones with no activity this period (§7.3). Empty for a leaf category/account (whose
   * only children, if any, are its own currency leaves — never listed as rows in their own right).
   */
  public List<TopLevelNode> childAccountCandidates(long parentId, boolean includeClosedAccounts) {
    return jdbcClient
        .sql(
            "select account_id::text as key, name as label, type,\n       "
                + ACCOUNT_HAS_CHILDREN_EXISTS
                + """
            from account
            where parent_id = :parentId
              and currency_leaf = false
              and person_leaf = false
              and deleted_at is null
              and (:includeClosedAccounts or closed_at is null)
            order by name
            """)
        .param(PARENT_ID, parentId)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  // ── tagTurnover (Tag has no closing balance — it is not an account) ────────

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
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
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
                       """
                + TURNOVER_AGGREGATES
                + """
                from tag_matches tm
                join posting p on p.posting_id = tm.posting_id
                join tag tg on tg.tag_id = tm.top_id
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
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
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /** Every live top-level tag — the {@code TAG} row candidates (see {@link #topLevelAccounts}). */
  public List<TopLevelNode> topLevelTags() {
    return jdbcClient
        .sql(
            "select tag_id::text as key, name as label, cast(null as text) as type,\n       "
                + TAG_HAS_CHILDREN_EXISTS
                + """
            from tag
            where parent_id is null
              and deleted_at is null
            order by name
            """)
        .query(TopLevelNode.class)
        .list();
  }

  // ── childTagTurnover (stage e nesting, §9.1) ────────────────────────────────

  /**
   * Turnover grouped by which direct child of {@code parentId} each posting's tag rolls up to,
   * <strong>plus</strong> a synthetic {@code (unspecified)} row (data-model §10.3, reporting.md
   * §9.3) for postings tagged directly on {@code parentId} with no child tag — tags are not
   * leaves-only, so expanding a tag needs a row for those. A posting carrying both {@code parentId}
   * directly and one of its child tags counts, correctly, in both rows (mirrors {@link
   * #tagTurnover} counting a posting once per top-level tag family it touches).
   */
  public List<RawTurnoverCell> childTagTurnover(
      long parentId,
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            TAG_ANCESTOR_CTE_FROM_PARENT
                + """
                ,
                tag_matches as (
                  select distinct pt.posting_id, tanc.top_id
                  from posting_tag pt
                  join tag_ancestor tanc on tanc.tag_id = pt.tag_id
                  union
                  select distinct pt.posting_id, :unspecifiedTopId as top_id
                  from posting_tag pt
                  where pt.tag_id = :parentId
                )
                select case when tm.top_id = :unspecifiedTopId
                            then :parentId::text || ':unspecified'
                            else tm.top_id::text end as dimension_key,
                       case when tm.top_id = :unspecifiedTopId then '(unspecified)'
                            else tg.name end as dimension_label,
                       cast(null as text) as dimension_type,
                       to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                       a.currency_code as currency_code,
                       """
                + TURNOVER_AGGREGATES
                + """
                from tag_matches tm
                join posting p on p.posting_id = tm.posting_id
                left join tag tg on tg.tag_id = tm.top_id
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
                + """
                group by dimension_key, dimension_label, month_key, a.currency_code
                """)
        .param(PARENT_ID, parentId)
        .param("unspecifiedTopId", UNSPECIFIED_TOP_ID)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * Every live direct child tag of {@code parentId}, plus the synthetic {@code (unspecified)}
   * candidate (§9.3) — the child-row candidates for expanding a {@code TAG} row one level (see
   * {@link #topLevelTags}), including ones with no activity this period (§7.3).
   */
  public List<TopLevelNode> childTagCandidates(long parentId) {
    List<TopLevelNode> nodes = new ArrayList<>();
    // "(unspecified)" sits where a real catch-all child would — a synthetic *first* child, not an
    // afterthought appended last (reporting.md §9.3). It is a data bucket, never itself expandable.
    nodes.add(new TopLevelNode(parentId + ":unspecified", "(unspecified)", null, false));
    nodes.addAll(
        jdbcClient
            .sql(
                "select tag_id::text as key, name as label, cast(null as text) as type,\n       "
                    + TAG_HAS_CHILDREN_EXISTS
                    + """
                from tag
                where parent_id = :parentId
                  and deleted_at is null
                order by name
                """)
            .param(PARENT_ID, parentId)
            .query(TopLevelNode.class)
            .list());
    return nodes;
  }

  // ── payeeTurnover (Payee has no closing balance — it is a transaction attribute) ──

  /**
   * Turnover grouped by each transaction's payee and month bucket. {@code payee_id} is nullable
   * (transfers carry none, data-model §3.5); those postings group under the synthetic {@code "(No
   * payee)"} row rather than being silently dropped from the total.
   */
  public List<RawTurnoverCell> payeeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select coalesce(t.payee_id::text, 'none') as dimension_key,
                   coalesce(pay.name, '(No payee)') as dimension_label,
                   cast(null as text) as dimension_type,
                   to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                   a.currency_code as currency_code,
                   """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                left join payee pay on pay.payee_id = t.payee_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
                + """
                group by t.payee_id, pay.name, month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * Every live payee plus the synthetic {@code "(No payee)"} candidate (see {@link #payeeTurnover})
   * — the {@code PAYEE} row candidates (see {@link #topLevelAccounts}).
   */
  public List<TopLevelNode> payeeCandidates() {
    List<TopLevelNode> nodes = new ArrayList<>();
    nodes.add(new TopLevelNode("none", "(No payee)", null));
    nodes.addAll(
        jdbcClient
            .sql(
                """
                select payee_id::text as key, name as label, cast(null as text) as type,
                       false as has_children
                from payee
                where deleted_at is null
                order by name
                """)
            .query(TopLevelNode.class)
            .list());
    return nodes;
  }

  // ── personTurnover / personClosingBalance (debt leaves only) ───────────────

  /**
   * Turnover grouped by the person who owns each per-person debt leaf (data-model §7), restricted
   * to postings on a {@code person_leaf} account. Always {@code asset}-natural (a debt leaf is
   * never any other type).
   */
  public List<RawTurnoverCell> personTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select per.person_id::text as dimension_key,
                   per.name as dimension_label,
                   'asset' as dimension_type,
                   to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                   a.currency_code as currency_code,
                   """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                join account_owner ao on ao.account_id = a.account_id
                join person per on per.person_id = ao.person_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + AND
                + "a.person_leaf = true\n"
                + extra.sql()
                + """
                group by per.person_id, per.name, month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /** The native closing balance of each person's debt leaves, as of {@code asOf}. */
  public List<RawBalanceCell> personClosingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select per.person_id::text as dimension_key,
                   per.name as dimension_label,
                   'asset' as dimension_type,
                   a.currency_code as currency_code,
                   sum(p.amount) as native_balance
            from posting p
            join transaction t on t.transaction_id = p.transaction_id
            join account a on a.account_id = p.account_id
            join account_owner ao on ao.account_id = a.account_id
            join person per on per.person_id = ao.person_id
            where a.type in (:types)
              and a.deleted_at is null
              and (:includeClosedAccounts or a.closed_at is null)
              and t.deleted_at is null
              and (:includePendingReview or t.lifecycle = 'confirmed')
              and t.date <= :asOf
              and a.person_leaf = true
            """
                + extra.sql()
                + """
                group by per.person_id, per.name, a.currency_code
                """)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * Every live person with at least a provisioned debt leaf — the {@code PERSON} row candidates.
   */
  public List<TopLevelNode> personCandidates() {
    return jdbcClient
        .sql(
            """
            select distinct per.person_id::text as key, per.name as label, 'asset' as type,
                   false as has_children
            from person per
            join account_owner ao on ao.person_id = per.person_id
            where per.deleted_at is null
            order by label
            """)
        .query(TopLevelNode.class)
        .list();
  }

  // ── currencyTurnover / currencyClosingBalance ──────────────────────────────

  /** Turnover grouped by each posting's account's currency and month bucket. */
  public List<RawTurnoverCell> currencyTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.currency_code as dimension_key,
                   a.currency_code as dimension_label,
                   cast(null as text) as dimension_type,
                   to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                   a.currency_code as currency_code,
                   """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
                + """
                group by a.currency_code, month_key
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * The native closing balance of every in-scope account, grouped by currency, as of {@code asOf}.
   */
  public List<RawBalanceCell> currencyClosingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.currency_code as dimension_key,
                   a.currency_code as dimension_label,
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
            """
                + extra.sql()
                + """
                group by a.currency_code
                """)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * Every defined currency — the {@code CURRENCY} row candidates (see {@link #topLevelAccounts}).
   */
  public List<TopLevelNode> currencyCandidates() {
    return jdbcClient
        .sql(
            """
            select currency_code as key, currency_code as label, cast(null as text) as type,
                   false as has_children
            from currency
            order by currency_code
            """)
        .query(TopLevelNode.class)
        .list();
  }

  // ── accountTypeTurnover / accountTypeClosingBalance ────────────────────────

  /** Turnover grouped by each posting's account's {@code type} and month bucket. */
  public List<RawTurnoverCell> accountTypeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.type as dimension_key,
                   a.type as dimension_label,
                   a.type as dimension_type,
                   to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                   a.currency_code as currency_code,
                   """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
                + """
                group by a.type, month_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawTurnoverCell.class)
        .list();
  }

  /**
   * The native closing balance of every in-scope account, grouped by {@code type}, as of {@code
   * asOf}.
   */
  public List<RawBalanceCell> accountTypeClosingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.type as dimension_key,
                   a.type as dimension_label,
                   a.type as dimension_type,
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
            """
                + extra.sql()
                + """
                group by a.type, a.currency_code
                """)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  // ── totalTurnover / totalClosingBalance (no dimension) ─────────────────────

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
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select 'total' as dimension_key,
                   'Total' as dimension_label,
                   cast(null as text) as dimension_type,
                   to_char(date_trunc('month', t.date), 'YYYY-MM') as month_key,
                   a.currency_code as currency_code,
                   """
                + TURNOVER_AGGREGATES
                + """
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + RATE_LATERAL_JOIN
                + WHERE
                + SCOPE_PREDICATE
                + AND
                + LEG_PREDICATE
                + "\n"
                + extra.sql()
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
        .params(extra.params())
        .query(RawTurnoverCell.class)
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
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
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
            """
                + extra.sql()
                + """
                group by a.currency_code
                """)
        .param(TYPES, types)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(AS_OF, asOf)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  // ── filter compilation (§6.2–§6.3) ──────────────────────────────────────────

  /**
   * The live subtree of {@code roots}: the roots themselves and every live descendant, walked to
   * arbitrary depth via {@code parent_id} (data-model §5) — a {@code CATEGORY}/{@code ACCOUNT}
   * filter's {@code IS_ONE_OF} subtree semantics (§6.3).
   */
  private List<Long> subtreeAccountIds(List<Long> roots) {
    if (roots.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            with recursive subtree(account_id) as (
              select account_id from account where account_id in (:roots) and deleted_at is null
              union all
              select a.account_id from account a
              join subtree s on a.parent_id = s.account_id
              where a.deleted_at is null
            )
            select account_id from subtree
            """)
        .param("roots", roots)
        .query(Long.class)
        .list();
  }

  /** Mirrors {@link #subtreeAccountIds} for the tag tree (data-model §10.3). */
  private List<Long> subtreeTagIds(List<Long> roots) {
    if (roots.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            with recursive subtree(tag_id) as (
              select tag_id from tag where tag_id in (:roots) and deleted_at is null
              union all
              select tg.tag_id from tag tg
              join subtree s on tg.parent_id = s.tag_id
              where tg.deleted_at is null
            )
            select tag_id from subtree
            """)
        .param("roots", roots)
        .query(Long.class)
        .list();
  }

  /**
   * Compiles a {@link QueryConstraints} into one extra {@code and}-prefixed SQL fragment plus its
   * bind params. Every turnover/closing-balance method appends {@link CompiledExtra#sql()} to its
   * own WHERE clause (right before {@code group by}) and merges {@link CompiledExtra#params()} in.
   */
  private CompiledExtra compileExtra(QueryConstraints constraints) {
    StringBuilder sql = new StringBuilder(128);
    Map<String, Object> params = new LinkedHashMap<>();
    List<ReportFilter> filters = constraints.filters();
    for (int i = 0; i < filters.size(); i++) {
      sql.append(AND).append(filterPredicate(filters.get(i), i, params)).append('\n');
    }
    return new CompiledExtra(sql.toString(), params);
  }

  /**
   * One predicate per {@link FilterField} (§6.3) — a flat enum dispatch, not decision complexity.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  private String filterPredicate(ReportFilter filter, int index, Map<String, Object> params) {
    String key = "filter" + index;
    return switch (filter.field()) {
      case CATEGORY, ACCOUNT -> accountHierarchyPredicate(filter, key, params);
      case TAG -> tagPredicate(filter, key, params);
      case PAYEE -> payeePredicate(filter, key, params);
      case PERSON -> personPredicate(filter, key, params);
      case CURRENCY -> currencyFilterPredicate(filter, key, params);
      case ACCOUNT_TYPE -> accountTypeFilterPredicate(filter, key, params);
      case RECONCILIATION -> reconciliationPredicate(filter, key, params);
      case NOTE -> notePredicate(filter, key, params);
    };
  }

  /**
   * A recursive subtree lookup can come back empty (e.g. a since-deleted root); {@code in ()} is
   * invalid SQL, and no live row ever has this id, so binding it makes the predicate simply always
   * false rather than a query error.
   */
  private static List<Long> orNoMatch(List<Long> ids) {
    return ids.isEmpty() ? NO_MATCH : ids;
  }

  /**
   * A {@link FilterLevel#TRANSACTION} predicate: the transaction qualifies the moment any posting
   * of it — reached via a private {@code fp_<key>} alias, never the measured posting's own join —
   * satisfies {@code extraJoin}/{@code extraPredicate} (§6.2's worked example).
   */
  private static String existsAcrossTransaction(
      String key, String extraJoin, String extraPredicate) {
    String alias = "fp_" + key;
    return "exists (select 1 from posting "
        + alias
        + extraJoin
        + " where "
        + alias
        + ".transaction_id = t.transaction_id and "
        + extraPredicate
        + ")";
  }

  /**
   * The shape almost every filter predicate shares (§6.2): posting-level restricts the measured
   * posting/account directly; transaction-level wraps the equivalent condition, reached through
   * {@code join}'s own alias, in {@link #existsAcrossTransaction}.
   */
  private static String postingOrTransaction(
      FilterLevel level,
      String key,
      String postingPredicate,
      String join,
      String transactionPredicate) {
    return level == FilterLevel.POSTING
        ? postingPredicate
        : existsAcrossTransaction(key, join, transactionPredicate);
  }

  /** {@code CATEGORY}/{@code ACCOUNT}: {@code IS_ONE_OF} expands to subtree membership (§6.3). */
  private String accountHierarchyPredicate(
      ReportFilter filter, String key, Map<String, Object> params) {
    List<Long> roots = filter.values().stream().map(Long::parseLong).toList();
    params.put(key, orNoMatch(subtreeAccountIds(roots)));
    return postingOrTransaction(
        filter.level(),
        key,
        "a.account_id in (:" + key + ")",
        "",
        "fp_" + key + ".account_id in (:" + key + ")");
  }

  /** {@code TAG}: {@code IS_ONE_OF} expands to subtree membership, same as a hierarchy account. */
  private String tagPredicate(ReportFilter filter, String key, Map<String, Object> params) {
    List<Long> roots = filter.values().stream().map(Long::parseLong).toList();
    params.put(key, orNoMatch(subtreeTagIds(roots)));
    String alias = "fpt_" + key;
    String join =
        " join posting_tag " + alias + ON + alias + ".posting_id = fp_" + key + ".posting_id";
    return postingOrTransaction(
        filter.level(),
        key,
        "p.posting_id in (select posting_id from posting_tag where tag_id in (:" + key + "))",
        join,
        alias + ".tag_id in (:" + key + ")");
  }

  /**
   * {@code PAYEE}: {@code IS_ONE_OF} takes payee ids (a payee is not a hierarchy); {@code MATCHES}
   * is a case-insensitive regex against the payee's name (§6.3). {@code payee_id} lives on {@code
   * transaction}, so the level makes no difference — both readings are the same predicate.
   */
  private String payeePredicate(ReportFilter filter, String key, Map<String, Object> params) {
    if (filter.operator() == FilterOperator.MATCHES) {
      params.put(key, filter.values().get(0));
      return "t.payee_id in (select payee_id from payee where name ~* :" + key + ")";
    }
    params.put(key, filter.values().stream().map(Long::parseLong).toList());
    return "t.payee_id in (:" + key + ")";
  }

  /**
   * {@code PERSON}: {@code IS_ONE_OF} takes person ids (no subtree — a person is not a hierarchy).
   */
  private String personPredicate(ReportFilter filter, String key, Map<String, Object> params) {
    params.put(key, filter.values().stream().map(Long::parseLong).toList());
    String alias = "fao_" + key;
    String join =
        " join account_owner " + alias + ON + alias + ".account_id = fp_" + key + ".account_id";
    return postingOrTransaction(
        filter.level(),
        key,
        "a.account_id in (select account_id from account_owner where person_id in (:" + key + "))",
        join,
        alias + ".person_id in (:" + key + ")");
  }

  private String currencyFilterPredicate(
      ReportFilter filter, String key, Map<String, Object> params) {
    params.put(key, filter.values());
    String alias = "fa_" + key;
    String join = " join account " + alias + ON + alias + ".account_id = fp_" + key + ".account_id";
    return postingOrTransaction(
        filter.level(),
        key,
        "a.currency_code in (:" + key + ")",
        join,
        alias + ".currency_code in (:" + key + ")");
  }

  /**
   * {@code ACCOUNT_TYPE} is transaction-level only (§6.2's exception): its posting-level reading is
   * exactly what scope's account types already say, so it is always compiled as an {@code exists}
   * over the transaction's own postings — {@link ReportFilter}'s constructor already refuses any
   * other level for this field.
   */
  private String accountTypeFilterPredicate(
      ReportFilter filter, String key, Map<String, Object> params) {
    params.put(key, filter.values());
    String alias = "fa_" + key;
    String join = " join account " + alias + ON + alias + ".account_id = fp_" + key + ".account_id";
    return existsAcrossTransaction(key, join, alias + ".type in (:" + key + ")");
  }

  private String reconciliationPredicate(
      ReportFilter filter, String key, Map<String, Object> params) {
    params.put(key, filter.values());
    return postingOrTransaction(
        filter.level(),
        key,
        "p.reconciliation in (:" + key + ")",
        "",
        "fp_" + key + ".reconciliation in (:" + key + ")");
  }

  /**
   * {@code CONTAINS}: a case-insensitive substring match, escaping the requester's own {@code %}/
   * {@code _}/{@code \} so a stray wildcard in typed text cannot widen the match. The level picks
   * which column carries the note — {@code transaction.note} or {@code posting.note} both exist
   * (data-model §3.5–§3.6).
   */
  private String notePredicate(ReportFilter filter, String key, Map<String, Object> params) {
    String escaped =
        filter.values().get(0).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    params.put(key, "%" + escaped + "%");
    String column = filter.level() == FilterLevel.POSTING ? "p.note" : "t.note";
    return column + " ilike :" + key + " escape '\\'";
  }

  /** One compiled {@link QueryConstraints}: the extra SQL to append, and its bind params. */
  private record CompiledExtra(String sql, Map<String, Object> params) {}
}
