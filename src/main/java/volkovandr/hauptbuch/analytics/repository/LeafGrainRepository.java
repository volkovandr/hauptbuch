package volkovandr.hauptbuch.analytics.repository;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.analytics.DateGranularity;

/**
 * Native-SQL access for a Report's raw export (reporting.md §13): turnover and closing balance at
 * leaf grain — by each posting's own account, payee and, when asked, tag — plus the labels a leaf
 * is exported under. One query however deep the trees, so the export's cost does not grow with
 * them. The scope, leg and filter predicates and the base valuation are {@link
 * ReportQueryRepository}'s own, so the leaves add up to the rows the Report shows.
 */
@Repository
public class LeafGrainRepository {

  private final JdbcClient jdbcClient;
  private final ReportQueryRepository reportQueries;

  LeafGrainRepository(JdbcClient jdbcClient, ReportQueryRepository reportQueries) {
    this.jdbcClient = jdbcClient;
    this.reportQueries = reportQueries;
  }

  /**
   * What a leaf-grain turnover query reads, beside its constraints.
   *
   * @param types the account types in scope
   * @param start the range's first day
   * @param end the range's last day
   * @param granularity the Date ladder's rung
   * @param baseCurrency the book's base currency
   * @param leg {@code NET}, {@code DEBITS} or {@code CREDITS}
   * @param includeClosedAccounts whether closed accounts count
   * @param includePendingReview whether pending-review transactions count
   */
  public record TurnoverQuery(
      List<String> types,
      LocalDate start,
      LocalDate end,
      DateGranularity granularity,
      String baseCurrency,
      String leg,
      boolean includeClosedAccounts,
      boolean includePendingReview) {

    /** Defensively copies the types. */
    public TurnoverQuery {
      types = List.copyOf(types);
    }
  }

  /**
   * Turnover grouped by each posting's own account, its transaction's payee and the Date bucket —
   * and, when {@code byTag}, each of the posting's own live tags: a posting with two tags in both
   * groups, an untagged one in none, as the Tag dimension counts them (§9.3).
   */
  public List<LeafTurnoverFact> turnover(
      TurnoverQuery query, boolean byTag, QueryConstraints constraints) {
    ReportQueryRepository.CompiledExtra extra = reportQueries.compileExtra(constraints);
    String tagExpr = byTag ? "pt.tag_id" : "cast(null as bigint)";
    String tagJoin =
        byTag
            ? """
              join posting_tag pt on pt.posting_id = p.posting_id
              join tag tg on tg.tag_id = pt.tag_id and tg.deleted_at is null
              """
            : "";
    return jdbcClient
        .sql(
            "select a.account_id,\n       "
                + tagExpr
                + " as tag_id,\n       t.payee_id,\n       "
                + ReportQueryRepository.BUCKET_KEY_AND_CURRENCY_COLUMNS
                + """
                sum(p.amount) as native_amount,
                       sum("""
                + ReportQueryRepository.BASE_AMOUNT_EXPR
                + """
                ) as base_amount,
                       count(*) filter (where """
                + ReportQueryRepository.MISSING_RATE_EXPR
                + """
                ) as missing_rate_count,
                       count(*) as posting_count,
                       array_agg(distinct p.transaction_id) as transaction_ids
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + tagJoin
                + ReportQueryRepository.RATE_LATERAL_JOIN
                + "where "
                + ReportQueryRepository.SCOPE_PREDICATE
                + "  and "
                + ReportQueryRepository.LEG_PREDICATE
                + "\n"
                + extra.sql()
                + "group by a.account_id, "
                + tagExpr
                + ", t.payee_id, bucket_key, a.currency_code\n")
        .param("types", query.types())
        .param("startDate", query.start())
        .param("endDate", query.end())
        .param("baseCurrency", query.baseCurrency())
        .param("leg", query.leg())
        .param("includeClosedAccounts", query.includeClosedAccounts())
        .param("includePendingReview", query.includePendingReview())
        .param("bucketUnit", query.granularity().sqlUnit())
        .param("bucketFormat", query.granularity().sqlFormat())
        .params(extra.params())
        .query(
            (rs, rowNum) ->
                new LeafTurnoverFact(
                    rs.getLong("account_id"),
                    rs.getObject("tag_id", Long.class),
                    rs.getObject("payee_id", Long.class),
                    rs.getString("bucket_key"),
                    rs.getString("currency_code"),
                    rs.getBigDecimal("native_amount"),
                    rs.getBigDecimal("base_amount"),
                    rs.getLong("missing_rate_count"),
                    rs.getLong("posting_count"),
                    Arrays.asList((Long[]) rs.getArray("transaction_ids").getArray())))
        .list();
  }

  /** Each account's native closing balance as of {@code asOf}. */
  public List<LeafBalanceFact> closingBalance(
      List<String> types,
      LocalDate asOf,
      boolean includeClosedAccounts,
      boolean includePendingReview,
      QueryConstraints constraints) {
    ReportQueryRepository.CompiledExtra extra = reportQueries.compileExtra(constraints);
    return jdbcClient
        .sql(
            """
            select a.account_id,
                   a.currency_code,
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
                group by a.account_id, a.currency_code
                """)
        .param("types", types)
        .param("asOf", asOf)
        .param("includeClosedAccounts", includeClosedAccounts)
        .param("includePendingReview", includePendingReview)
        .params(extra.params())
        .query(LeafBalanceFact.class)
        .list();
  }

  /** Every account with its path from the root, and a debt leaf's owner (§13's leaf labels). */
  public List<LeafAccount> accounts() {
    return jdbcClient
        .sql(
            """
            with recursive account_path(account_id, path) as (
              select account_id, name::text from account where parent_id is null
              union all
              select a.account_id, ap.path || ':' || a.name
              from account a
              join account_path ap on ap.account_id = a.parent_id
            )
            select a.account_id,
                   ap.path,
                   a.type,
                   a.currency_code,
                   per.person_id,
                   per.name as person_name
            from account a
            join account_path ap on ap.account_id = a.account_id
            left join account_owner ao on ao.account_id = a.account_id and a.person_leaf
            left join person per on per.person_id = ao.person_id
            """)
        .query(LeafAccount.class)
        .list();
  }

  /** Every live tag, keyed by its id and labelled by its path from the root. */
  public List<TopLevelNode> tags() {
    return jdbcClient
        .sql(
            """
            with recursive tag_path(tag_id, path) as (
              select tag_id, name::text from tag where parent_id is null and deleted_at is null
              union all
              select tg.tag_id, tp.path || ':' || tg.name
              from tag tg
              join tag_path tp on tp.tag_id = tg.parent_id
              where tg.deleted_at is null
            )
            select tag_id::text as key, path as label, cast(null as text) as type,
                   false as has_children
            from tag_path
            """)
        .query(TopLevelNode.class)
        .list();
  }

  /** Every live payee, keyed by its id — the Payee axis's own candidates (reporting.md §4). */
  public List<TopLevelNode> payees() {
    return jdbcClient
        .sql(
            """
            select payee_id::text as key, name as label, cast(null as text) as type,
                   false as has_children
            from payee
            where deleted_at is null
            """)
        .query(TopLevelNode.class)
        .list();
  }
}
