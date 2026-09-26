package volkovandr.hauptbuch.analytics.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.analytics.DateGranularity;
import volkovandr.hauptbuch.analytics.FilterLevel;
import volkovandr.hauptbuch.analytics.FilterOperator;
import volkovandr.hauptbuch.analytics.ReportFilter;

/**
 * Native-SQL access for the report engine (reporting.md §14): grouped reads of {@code posting} /
 * {@code transaction} / {@code account} / {@code tag} / {@code payee} / {@code person} / {@code
 * currency}, dimension-agnostic per {@link RawTurnoverCell} — the query always returns both the
 * grouped dimension value and the Date bucket, and {@code ReportEngine} decides which axis each
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
  private static final String BUCKET_UNIT = "bucketUnit";
  private static final String BUCKET_FORMAT = "bucketFormat";
  private static final String PROMOTED_ACCOUNT_IDS = "promotedAccountIds";
  private static final String PROMOTED_TAG_IDS = "promotedTagIds";
  private static final String PERSON_ID = "personId";
  private static final String PERSON_NAME = "per.name";

  /** The {@code PERSON} dimension's key: the owner's bare id. */
  private static final String PERSON_ID_KEY = "per.person_id::text";

  /** A person's key beneath "Personal debts" ({@link NodeKey#personKey}). */
  private static final String PERSON_KEY = "'" + NodeKey.PERSON_PREFIX + "' || per.person_id::text";

  /** A person's own debt leaf, beneath their person node. */
  private static final String LEAF_KEY = "a.account_id::text";

  /** Restricts the debt-leaf queries to one person's own leaves. */
  private static final String ONE_PERSON = AND + "ao.person_id = :personId\n";

  /** The debt-leaf queries' joins: each posting's account and its owner. */
  private static final String DEBT_LEAF_JOINS =
      """
      from posting p
      join transaction t on t.transaction_id = p.transaction_id
      join account a on a.account_id = p.account_id
      join account_owner ao on ao.account_id = a.account_id
      join person per on per.person_id = ao.person_id
      """;

  /**
   * The Date bucket a turnover row groups by (reporting.md §8.2, stage e4) — {@link
   * DateGranularity#sqlUnit()}/{@link DateGranularity#sqlFormat()} bound as parameters rather than
   * spliced into the SQL text, so every turnover query shares one literal template regardless of
   * which rung of the ladder it buckets at. {@code date_trunc}'s first argument accepts a bound
   * text parameter the same way any other {@code text} argument does.
   */
  private static final String BUCKET_KEY_EXPR =
      "to_char(date_trunc(:bucketUnit, t.date), :bucketFormat) as bucket_key";

  /**
   * {@link #BUCKET_KEY_EXPR} plus the {@code currency_code} column every turnover query selects
   * right after it — shared so the two don't drift, and so the two literal fragments PMD's {@code
   * AvoidDuplicateLiterals} would otherwise flag (repeated once per turnover method) live in one
   * place instead.
   */
  private static final String BUCKET_KEY_AND_CURRENCY_COLUMNS =
      BUCKET_KEY_EXPR + ",\n       a.currency_code as currency_code,\n       ";

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
   * level) — shared by every account-tree query so row grouping and labelling cannot drift apart. A
   * promoted node ({@link QueryConstraints#promotedAccountIds()}, reporting issue 08) is a
   * top-level ancestor of its own, and the walk down from a real root stops at it, so each account
   * still rolls up to exactly one top-level node.
   */
  private static final String ACCOUNT_ANCESTOR_CTE =
      """
      with recursive account_ancestor(account_id, top_id) as (
        select account_id, account_id from account
        where parent_id is null or account_id in (:promotedAccountIds)
        union all
        select a.account_id, anc.top_id
        from account a
        join account_ancestor anc on a.parent_id = anc.account_id
        where a.account_id not in (:promotedAccountIds)
      )
      """;

  private static final String PERSONAL_DEBTS_BUCKET =
      "a.person_leaf and a.account_id not in (:promotedAccountIds)";

  /**
   * A per-person debt leaf (data-model §7) is its own root in {@link #ACCOUNT_ANCESTOR_CTE} —
   * {@code parent_id is null} like a real top-level account — so without this, the {@code
   * CATEGORY}/{@code ACCOUNT} dimension would list every person's leaf individually under its
   * cosmetic, non-owner name ({@code personal.<CUR>}, data-model §7). Every debt leaf groups under
   * the one synthetic "Personal debts" node instead ({@link NodeKey#PERSONAL_DEBTS}, reporting
   * issue 06), which expands to people ({@link #debtPeopleCandidates}) and then to each person's
   * leaves ({@link #debtLeafCandidates}). A debt leaf ticked in its own right (reporting issue 08)
   * is a promoted node like any other, and keeps its own key.
   */
  private static final String ACCOUNT_DIMENSION_KEY =
      "case when "
          + PERSONAL_DEBTS_BUCKET
          + " then '"
          + NodeKey.PERSONAL_DEBTS
          + "' else anc.top_id::text end";

  private static final String ACCOUNT_DIMENSION_LABEL =
      "case when "
          + PERSONAL_DEBTS_BUCKET
          + " then '"
          + NodeKey.PERSONAL_DEBTS_LABEL
          + "' else top.name end";

  private static final String ACCOUNT_DIMENSION_TYPE =
      "case when " + PERSONAL_DEBTS_BUCKET + " then a.type else top.type end";

  /**
   * Every tag's top-level ancestor, mirroring {@link #ACCOUNT_ANCESTOR_CTE} for the tag tree
   * (data-model §10.3), promoted tags included.
   */
  private static final String TAG_ANCESTOR_CTE =
      """
      with recursive tag_ancestor(tag_id, top_id) as (
        select tag_id, tag_id from tag
        where (parent_id is null or tag_id in (:promotedTagIds)) and deleted_at is null
        union all
        select tg.tag_id, anc.top_id
        from tag tg
        join tag_ancestor anc on tg.parent_id = anc.tag_id
        where tg.deleted_at is null and tg.tag_id not in (:promotedTagIds)
      )
      """;

  /**
   * {@link #ACCOUNT_ANCESTOR_CTE} generalized to an arbitrary seed instead of the root: every
   * descendant of {@code :parentId}, grouped by which of {@code :parentId}'s <em>direct</em>
   * children it rolls up to — expanding one node one level (reporting.md §9.1). No person-leaf
   * special case here (unlike {@link #ACCOUNT_ANCESTOR_CTE}): a debt leaf is always its own root
   * (data-model §7), so it can never appear as a descendant of a real category/account node. A
   * promoted node is skipped, like {@link #ACCOUNT_ANCESTOR_CTE} skips it: it is a top-level node
   * of its own, not part of the expanded node's children.
   */
  private static final String ACCOUNT_ANCESTOR_CTE_FROM_PARENT =
      """
      with recursive account_ancestor(account_id, top_id) as (
        select account_id, account_id from account
        where parent_id = :parentId and account_id not in (:promotedAccountIds)
        union all
        select a.account_id, anc.top_id
        from account a
        join account_ancestor anc on a.parent_id = anc.account_id
        where a.account_id not in (:promotedAccountIds)
      )
      """;

  /**
   * {@link #TAG_ANCESTOR_CTE} generalized to an arbitrary seed instead of the root, mirroring
   * {@link #ACCOUNT_ANCESTOR_CTE_FROM_PARENT} for the tag tree: every descendant of {@code
   * :parentId}, grouped by which of {@code :parentId}'s direct children it rolls up to, promoted
   * tags skipped.
   */
  private static final String TAG_ANCESTOR_CTE_FROM_PARENT =
      """
      with recursive tag_ancestor(tag_id, top_id) as (
        select tag_id, tag_id from tag
        where parent_id = :parentId and deleted_at is null and tag_id not in (:promotedTagIds)
        union all
        select tg.tag_id, anc.top_id
        from tag tg
        join tag_ancestor anc on tg.parent_id = anc.tag_id
        where tg.deleted_at is null and tg.tag_id not in (:promotedTagIds)
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
   * missing-rate count read the same resolved rate. Shared, with {@link #BASE_AMOUNT_EXPR} and
   * {@link #MISSING_RATE_EXPR}, by {@link PostingValueRepository}, so a drill-down list values each
   * posting exactly as its cell summed it.
   */
  static final String RATE_LATERAL_JOIN =
      """
      left join lateral (
        select er.rate
        from exchange_rate er
        where er.currency_code = a.currency_code and er.date <= t.date
        order by er.date desc
        limit 1
      ) rate on a.currency_code <> :baseCurrency
      """;

  static final String BASE_AMOUNT_EXPR =
      """
      coalesce(p.base_amount,
        case when a.currency_code = :baseCurrency then p.amount else p.amount * rate.rate end)
      """;

  static final String MISSING_RATE_EXPR =
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
             count(distinct p.transaction_id) as transaction_count,
             coalesce(array_agg(p.posting_id) filter (where :collectPostingIds), '{}')
               as posting_ids
      """;

  /**
   * Every turnover query's row mapper. Spelled out rather than {@code query(RawTurnoverCell.class)}
   * because {@code posting_ids} is a Postgres {@code bigint[]}, which the property-based mapper
   * cannot convert to a {@code List<Long>}.
   */
  private static final RowMapper<RawTurnoverCell> RAW_TURNOVER_CELL =
      (rs, rowNum) ->
          new RawTurnoverCell(
              rs.getString("dimension_key"),
              rs.getString("dimension_label"),
              rs.getString("dimension_type"),
              rs.getString("bucket_key"),
              rs.getString("currency_code"),
              rs.getBigDecimal("native_amount"),
              rs.getBigDecimal("base_amount"),
              rs.getLong("missing_rate_count"),
              rs.getLong("posting_count"),
              rs.getLong("transaction_count"),
              postingIds(rs));

  private final JdbcClient jdbcClient;

  ReportQueryRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  // ── accountTreeTurnover / accountTreeClosingBalance (Category/Account) ─────

  /**
   * Turnover grouped by each posting's account's top-level ancestor and Date bucket — the
   * category×month matrix's query (reporting.md §16), and equally the {@code ACCOUNT} dimension
   * when {@code types} is the asset/liability/equity set instead of income/expense. Buckets at
   * month granularity; every pre-e4 caller's own convenience overload.
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
    return accountTreeTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #accountTreeTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) accountTreeTurnover}, bucketing at {@code granularity} instead of always
   * month (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> accountTreeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
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
                + " as dimension_type,\n       "
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by dimension_key, dimension_label, dimension_type, bucket_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
   * row can be suppressed rather than simply never listed (reporting.md §7.3) — plus the one
   * "Personal debts" node when any debt leaf is in scope (see {@link #ACCOUNT_DIMENSION_KEY}).
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
            select :personalDebtsKey as key, :personalDebtsLabel as label, 'asset' as type,
                   true as has_children
            where exists(
              select 1 from account
              where type in (:types)
                and person_leaf = true
                and deleted_at is null
                and (:includeClosedAccounts or closed_at is null)
            )
            order by label
            """)
        .param("personalDebtsKey", NodeKey.PERSONAL_DEBTS)
        .param("personalDebtsLabel", NodeKey.PERSONAL_DEBTS_LABEL)
        .param(TYPES, types)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  // ── childAccountTurnover / childAccountClosingBalance (stage e nesting, §9.1) ──

  /**
   * Turnover grouped by which direct child of {@code parentId} each posting's account rolls up to
   * (reporting.md §9.1) — {@link #accountTreeTurnover} seeded at an arbitrary node instead of the
   * root, for expanding one {@code CATEGORY}/{@code ACCOUNT} row into its own children. Buckets at
   * month granularity; every pre-e4 caller's own convenience overload.
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
    return childAccountTurnover(
        parentId,
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #childAccountTurnover(long, List, LocalDate, LocalDate, String, String, boolean,
   * boolean, QueryConstraints) childAccountTurnover}, bucketing at {@code granularity} instead of
   * always month (reporting.md §8.2, stage e4).
   */
  // ExcessiveParameterList: a flat list of scalar SQL bind params, the established shape every
  // turnover method here takes — this is the one child-seeded overload that also carries parentId
  // and granularity, pushing it past the threshold; splitting it would just wrap this same list in
  // a context record, not reduce it.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public List<RawTurnoverCell> childAccountTurnover(
      long parentId,
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            ACCOUNT_ANCESTOR_CTE_FROM_PARENT
                + """
                select anc.top_id::text as dimension_key,
                       top.name as dimension_label,
                       top.type as dimension_type,
                       """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by dimension_key, dimension_label, dimension_type, bucket_key, a.currency_code
                """)
        .param(PARENT_ID, parentId)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
   * Leaves out {@code promotedIds} (reporting issue 08): a promoted node is a top-level node of its
   * own, so it is never also listed under its real parent.
   */
  public List<TopLevelNode> childAccountCandidates(
      long parentId, boolean includeClosedAccounts, List<Long> promotedIds) {
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
              and account_id not in (:promotedAccountIds)
            order by name
            """)
        .param(PARENT_ID, parentId)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(PROMOTED_ACCOUNT_IDS, orNoMatch(promotedIds))
        .query(TopLevelNode.class)
        .list();
  }

  /**
   * The ticked nodes of a Category/Account filter on the axis dimension's own field, as that axis's
   * top level (reporting issue 08) — listed even with no activity this period (§7.3), each labelled
   * with its full path ({@code Food:Restaurants}) since its parent is not on the axis to say where
   * it sits. Only nodes of the given {@code types} (the dimension's own, reporting.md §4).
   */
  public List<TopLevelNode> promotedAccountCandidates(
      List<Long> ids, List<String> types, boolean includeClosedAccounts) {
    return jdbcClient
        .sql(
            """
            with recursive path(account_id, ancestor_id, label) as (
              select account_id, parent_id, name::text from account where account_id in (:ids)
              union all
              select path.account_id, a.parent_id, a.name || ':' || path.label
              from path
              join account a on a.account_id = path.ancestor_id
            )
            select account.account_id::text as key, path.label as label, account.type,
            """
                + ACCOUNT_HAS_CHILDREN_EXISTS
                + """
            from account
            join path on path.account_id = account.account_id and path.ancestor_id is null
            where account.type in (:types)
              and account.deleted_at is null
              and (:includeClosedAccounts or account.closed_at is null)
            order by label
            """)
        .param("ids", orNoMatch(ids))
        .param(TYPES, types)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  // ── tagTurnover (Tag has no closing balance — it is not an account) ────────

  /**
   * Turnover grouped by each posting's top-level tag and Date bucket. A posting distinctly carries
   * one row per top-level tag family it touches ({@code distinct} on posting/top-tag before the
   * join to the measured posting), so a posting tagged both {@code Car:Audi} and {@code Car:Skoda}
   * counts once under {@code Car} — never twice (data-model §10.3). Buckets at month granularity;
   * every pre-e4 caller's own convenience overload.
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
    return tagTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #tagTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) tagTurnover}, bucketing at {@code granularity} instead of always month
   * (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> tagTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
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
                       """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by tm.top_id, tg.name, bucket_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
   * #tagTurnover} counting a posting once per top-level tag family it touches). Buckets at month
   * granularity; every pre-e4 caller's own convenience overload.
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
    return childTagTurnover(
        parentId,
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #childTagTurnover(long, List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) childTagTurnover}, bucketing at {@code granularity} instead of always month
   * (reporting.md §8.2, stage e4).
   */
  // ExcessiveParameterList: see childAccountTurnover's own suppression above — the same shape,
  // for the same reason.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public List<RawTurnoverCell> childTagTurnover(
      long parentId,
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
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
                       """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by dimension_key, dimension_label, bucket_key, a.currency_code
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
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
        .list();
  }

  /**
   * Every live direct child tag of {@code parentId}, plus the synthetic {@code (unspecified)}
   * candidate (§9.3) — the child-row candidates for expanding a {@code TAG} row one level (see
   * {@link #topLevelTags}), including ones with no activity this period (§7.3), leaving out {@code
   * promotedIds} like {@link #childAccountCandidates} does.
   */
  public List<TopLevelNode> childTagCandidates(long parentId, List<Long> promotedIds) {
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
                  and tag_id not in (:promotedTagIds)
                order by name
                """)
            .param(PARENT_ID, parentId)
            .param(PROMOTED_TAG_IDS, orNoMatch(promotedIds))
            .query(TopLevelNode.class)
            .list());
    return nodes;
  }

  /**
   * {@link #promotedAccountCandidates}' own mirror for a Tag filter on the Tag axis: the ticked
   * tags, full-path labelled.
   */
  public List<TopLevelNode> promotedTagCandidates(List<Long> ids) {
    return jdbcClient
        .sql(
            """
            with recursive path(tag_id, ancestor_id, label) as (
              select tag_id, parent_id, name::text from tag where tag_id in (:ids)
              union all
              select path.tag_id, tg.parent_id, tg.name || ':' || path.label
              from path
              join tag tg on tg.tag_id = path.ancestor_id
            )
            select tag.tag_id::text as key, path.label as label, cast(null as text) as type,
            """
                + TAG_HAS_CHILDREN_EXISTS
                + """
            from tag
            join path on path.tag_id = tag.tag_id and path.ancestor_id is null
            where tag.deleted_at is null
            order by label
            """)
        .param("ids", orNoMatch(ids))
        .query(TopLevelNode.class)
        .list();
  }

  // ── payeeTurnover (Payee has no closing balance — it is a transaction attribute) ──

  /**
   * Turnover grouped by each transaction's payee and Date bucket. {@code payee_id} is nullable
   * (transfers carry none, data-model §3.5); those postings group under the synthetic {@code "(No
   * payee)"} row rather than being silently dropped from the total. Buckets at month granularity;
   * every pre-e4 caller's own convenience overload.
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
    return payeeTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #payeeTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) payeeTurnover}, bucketing at {@code granularity} instead of always month
   * (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> payeeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select coalesce(t.payee_id::text, 'none') as dimension_key,
                   coalesce(pay.name, '(No payee)') as dimension_label,
                   cast(null as text) as dimension_type,
                   """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by t.payee_id, pay.name, bucket_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
   * never any other type). Buckets at month granularity; every pre-e4 caller's own convenience
   * overload.
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
    return personTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #personTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) personTurnover}, bucketing at {@code granularity} instead of always month
   * (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> personTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(debtTurnoverSql(PERSON_ID_KEY, PERSON_NAME, "", extra))
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
        .sql(debtClosingBalanceSql(PERSON_ID_KEY, PERSON_NAME, "", extra))
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

  // ── the personal-debt tree: Personal debts → person → leaf (reporting issue 06) ──

  /**
   * The people beneath the "Personal debts" node ({@link #ACCOUNT_DIMENSION_KEY}): every person
   * owning a live debt leaf, soft-deleted people included — they keep their history (data-model
   * §7), and an empty one is suppression's call (§7.3). Keyed {@link NodeKey#personKey}.
   */
  public List<TopLevelNode> debtPeopleCandidates(boolean includeClosedAccounts) {
    return jdbcClient
        .sql(
            "select distinct "
                + PERSON_KEY
                + """
                 as key, per.name as label, 'asset' as type,
                       true as has_children
                from person per
                join account_owner ao on ao.person_id = per.person_id
                join account a on a.account_id = ao.account_id
                where a.person_leaf = true
                  and a.deleted_at is null
                  and (:includeClosedAccounts or a.closed_at is null)
                order by label
                """)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  /** One person's own live debt leaves, labelled by their currency — never expandable. */
  public List<TopLevelNode> debtLeafCandidates(long personId, boolean includeClosedAccounts) {
    return jdbcClient
        .sql(
            """
            select a.account_id::text as key, a.currency_code as label, a.type,
                   false as has_children
            from account a
            join account_owner ao on ao.account_id = a.account_id
            where ao.person_id = :personId
              and a.person_leaf = true
              and a.deleted_at is null
              and (:includeClosedAccounts or a.closed_at is null)
            order by label
            """)
        .param(PERSON_ID, personId)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .query(TopLevelNode.class)
        .list();
  }

  /**
   * Turnover of the debt leaves grouped by their owner — {@link #personTurnover} keyed {@link
   * NodeKey#personKey}, for expanding the "Personal debts" node one level.
   */
  public List<RawTurnoverCell> debtPeopleTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(debtTurnoverSql(PERSON_KEY, PERSON_NAME, "", extra))
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
        .list();
  }

  /** {@link #debtPeopleTurnover}'s closing balance, as of {@code asOf}. */
  public List<RawBalanceCell> debtPeopleClosingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(debtClosingBalanceSql(PERSON_KEY, PERSON_NAME, "", extra))
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * Turnover of one person's debt leaves grouped by leaf, labelled by currency — for expanding a
   * person one level beneath "Personal debts".
   */
  // ExcessiveParameterList: see childAccountTurnover's own suppression above — the same shape,
  // for the same reason.
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public List<RawTurnoverCell> debtLeafTurnover(
      long personId,
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(debtTurnoverSql(LEAF_KEY, "a.currency_code", ONE_PERSON, extra))
        .param(PERSON_ID, personId)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
        .list();
  }

  /** {@link #debtLeafTurnover}'s closing balance, as of {@code asOf}. */
  public List<RawBalanceCell> debtLeafClosingBalance(
      long personId,
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(debtClosingBalanceSql(LEAF_KEY, "a.currency_code", ONE_PERSON, extra))
        .param(PERSON_ID, personId)
        .param(TYPES, types)
        .param(AS_OF, asOf)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .params(extra.params())
        .query(RawBalanceCell.class)
        .list();
  }

  /**
   * Turnover of the debt leaves' postings ({@code p}/{@code a}/{@code t}, the owner as {@code
   * per}), grouped by {@code keyExpr}/{@code labelExpr} — shared by the {@code PERSON} dimension
   * and the personal-debt tree's two levels so the three cannot drift apart.
   */
  private static String debtTurnoverSql(
      String keyExpr, String labelExpr, String ownerPredicate, CompiledExtra extra) {
    return debtColumns(keyExpr, labelExpr)
        + BUCKET_KEY_AND_CURRENCY_COLUMNS
        + TURNOVER_AGGREGATES
        + DEBT_LEAF_JOINS
        + RATE_LATERAL_JOIN
        + WHERE
        + SCOPE_PREDICATE
        + AND
        + LEG_PREDICATE
        + AND
        + "a.person_leaf = true\n"
        + ownerPredicate
        + extra.sql()
        + "group by dimension_key, dimension_label, bucket_key, a.currency_code\n";
  }

  /** The debt-leaf queries' leading dimension columns, grouped by {@code keyExpr}. */
  private static String debtColumns(String keyExpr, String labelExpr) {
    return "select "
        + keyExpr
        + " as dimension_key,\n       "
        + labelExpr
        + " as dimension_label,\n       'asset' as dimension_type,\n       ";
  }

  /** {@link #debtTurnoverSql}'s closing-balance twin. */
  private static String debtClosingBalanceSql(
      String keyExpr, String labelExpr, String ownerPredicate, CompiledExtra extra) {
    return debtColumns(keyExpr, labelExpr)
        + """
        a.currency_code as currency_code,
               sum(p.amount) as native_balance
        """
        + DEBT_LEAF_JOINS
        + """
        where a.type in (:types)
          and a.deleted_at is null
          and (:includeClosedAccounts or a.closed_at is null)
          and t.deleted_at is null
          and (:includePendingReview or t.lifecycle = 'confirmed')
          and t.date <= :asOf
          and a.person_leaf = true
        """
        + ownerPredicate
        + extra.sql()
        + "group by dimension_key, dimension_label, a.currency_code\n";
  }

  // ── currencyTurnover / currencyClosingBalance ──────────────────────────────

  /**
   * Turnover grouped by each posting's account's currency and Date bucket. Buckets at month
   * granularity; every pre-e4 caller's own convenience overload.
   */
  public List<RawTurnoverCell> currencyTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    return currencyTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #currencyTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) currencyTurnover}, bucketing at {@code granularity} instead of always month
   * (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> currencyTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.currency_code as dimension_key,
                   a.currency_code as dimension_label,
                   cast(null as text) as dimension_type,
                   """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by a.currency_code, bucket_key
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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

  /**
   * Turnover grouped by each posting's account's {@code type} and Date bucket. Buckets at month
   * granularity; every pre-e4 caller's own convenience overload.
   */
  public List<RawTurnoverCell> accountTypeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    return accountTypeTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #accountTypeTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) accountTypeTurnover}, bucketing at {@code granularity} instead of always
   * month (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> accountTypeTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.type as dimension_key,
                   a.type as dimension_label,
                   a.type as dimension_type,
                   """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by a.type, bucket_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
   * per Date bucket and currency. Used when neither report axis carries a wired dimension (§5.2 —
   * sum is always legal over both axes for a flow measure, so this is never illegal, merely
   * coarse). Buckets at month granularity; every pre-e4 caller's own convenience overload.
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
    return totalTurnover(
        types,
        startDate,
        endDate,
        baseCurrency,
        leg,
        includeClosedAccounts,
        includePendingReview,
        DateGranularity.MONTH,
        constraints);
  }

  /**
   * {@link #totalTurnover(List, LocalDate, LocalDate, String, String, boolean, boolean,
   * QueryConstraints) totalTurnover}, bucketing at {@code granularity} instead of always month
   * (reporting.md §8.2, stage e4).
   */
  public List<RawTurnoverCell> totalTurnover(
      List<String> types,
      LocalDate startDate,
      LocalDate endDate,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      DateGranularity granularity,
      QueryConstraints constraints) {
    CompiledExtra extra = compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select 'total' as dimension_key,
                   'Total' as dimension_label,
                   cast(null as text) as dimension_type,
                   """
                + BUCKET_KEY_AND_CURRENCY_COLUMNS
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
                group by bucket_key, a.currency_code
                """)
        .param(TYPES, types)
        .param(START_DATE, startDate)
        .param(END_DATE, endDate)
        .param(BASE_CURRENCY, baseCurrency)
        .param(LEG, leg)
        .param(INCLUDE_CLOSED, includeClosedAccounts)
        .param(INCLUDE_PENDING_REVIEW, includePendingReview)
        .param(BUCKET_UNIT, granularity.sqlUnit())
        .param(BUCKET_FORMAT, granularity.sqlFormat())
        .params(extra.params())
        .query(RAW_TURNOVER_CELL)
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
   * filter's {@code IS_ONE_OF} subtree semantics (§6.3). The walk stops at a promoted node
   * (reporting issue 08) below a root, like {@link #ACCOUNT_ANCESTOR_CTE} does, so a nested axis's
   * synthetic filter on a real root never reaches into a node that is a top-level row of its own. A
   * user's own ticked nodes are never below one another, so their subtrees are unaffected.
   */
  private List<Long> subtreeAccountIds(List<Long> roots, List<Long> promotedIds) {
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
              where a.deleted_at is null and a.account_id not in (:promotedAccountIds)
            )
            select account_id from subtree
            """)
        .param("roots", roots)
        .param(PROMOTED_ACCOUNT_IDS, orNoMatch(promotedIds))
        .query(Long.class)
        .list();
  }

  private static List<Long> idsOf(List<NodeKey> nodes, NodeKey.Kind kind) {
    return nodes.stream().filter(n -> n.kind() == kind).map(NodeKey::id).toList();
  }

  /**
   * The live debt leaves of every person ({@code everyPerson}) or of {@code personIds} — what the
   * "Personal debts" node and a person beneath it stand for in a filter (reporting issue 06).
   */
  private List<Long> debtLeafIds(boolean everyPerson, List<Long> personIds) {
    if (!everyPerson && personIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            select a.account_id from account a
            where a.person_leaf = true
              and a.deleted_at is null
              and (:everyPerson or a.account_id in (
                select account_id from account_owner where person_id in (:personIds)))
            """)
        .param("everyPerson", everyPerson)
        .param("personIds", orNoMatch(personIds))
        .query(Long.class)
        .list();
  }

  /** Mirrors {@link #subtreeAccountIds} for the tag tree (data-model §10.3). */
  private List<Long> subtreeTagIds(List<Long> roots, List<Long> promotedIds) {
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
              where tg.deleted_at is null and tg.tag_id not in (:promotedTagIds)
            )
            select tag_id from subtree
            """)
        .param("roots", roots)
        .param(PROMOTED_TAG_IDS, orNoMatch(promotedIds))
        .query(Long.class)
        .list();
  }

  /**
   * Compiles a {@link QueryConstraints} into one extra {@code and}-prefixed SQL fragment plus its
   * bind params. Every turnover/closing-balance method appends {@link CompiledExtra#sql()} to its
   * own WHERE clause (right before {@code group by}) and merges {@link CompiledExtra#params()} in.
   * The params always carry the promoted node ids too (bound to a never-matching id when there are
   * none), which the account/tag ancestor CTEs read; a query that does not reference them ignores
   * them.
   */
  private CompiledExtra compileExtra(QueryConstraints constraints) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(PROMOTED_ACCOUNT_IDS, orNoMatch(constraints.promotedAccountIds()));
    params.put(PROMOTED_TAG_IDS, orNoMatch(constraints.promotedTagIds()));
    params.put("collectPostingIds", constraints.collectPostingIds());
    StringBuilder sql = new StringBuilder(128);
    List<ReportFilter> filters = constraints.filters();
    for (int i = 0; i < filters.size(); i++) {
      sql.append(AND).append(filterPredicate(filters.get(i), i, params, constraints)).append('\n');
    }
    return new CompiledExtra(sql.toString(), params);
  }

  /**
   * One predicate per {@link FilterField} (§6.3) — a flat enum dispatch, not decision complexity.
   */
  @SuppressWarnings("PMD.CyclomaticComplexity")
  private String filterPredicate(
      ReportFilter filter, int index, Map<String, Object> params, QueryConstraints constraints) {
    String key = "filter" + index;
    return switch (filter.field()) {
      case CATEGORY, ACCOUNT ->
          accountHierarchyPredicate(filter, key, params, constraints.promotedAccountIds());
      case TAG -> tagPredicate(filter, key, params, constraints.promotedTagIds());
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

  /**
   * {@code CATEGORY}/{@code ACCOUNT}: {@code IS_ONE_OF} expands to subtree membership (§6.3). A
   * value naming the "Personal debts" node or one person beneath it ({@link NodeKey}) stands for
   * those debt leaves — they have no real parent account whose subtree could say so (reporting
   * issues 06, 11).
   */
  private String accountHierarchyPredicate(
      ReportFilter filter, String key, Map<String, Object> params, List<Long> promotedIds) {
    List<NodeKey> nodes = filter.values().stream().map(NodeKey::parse).toList();
    List<Long> roots = idsOf(nodes, NodeKey.Kind.NODE);
    List<Long> accountIds = new ArrayList<>(subtreeAccountIds(roots, promotedIds));
    accountIds.addAll(
        debtLeafIds(
            nodes.stream().anyMatch(n -> n.kind() == NodeKey.Kind.PERSONAL_DEBTS),
            idsOf(nodes, NodeKey.Kind.PERSON)));
    params.put(key, orNoMatch(accountIds));
    return postingOrTransaction(
        filter.level(),
        key,
        "a.account_id in (:" + key + ")",
        "",
        "fp_" + key + ".account_id in (:" + key + ")");
  }

  /** {@code TAG}: {@code IS_ONE_OF} expands to subtree membership, same as a hierarchy account. */
  private String tagPredicate(
      ReportFilter filter, String key, Map<String, Object> params, List<Long> promotedIds) {
    List<Long> roots = filter.values().stream().map(Long::parseLong).toList();
    params.put(key, orNoMatch(subtreeTagIds(roots, promotedIds)));
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

  /** {@code posting_ids} as a list — empty when not collected. */
  private static List<Long> postingIds(ResultSet rs) throws SQLException {
    return Arrays.asList((Long[]) rs.getArray("posting_ids").getArray());
  }
}
