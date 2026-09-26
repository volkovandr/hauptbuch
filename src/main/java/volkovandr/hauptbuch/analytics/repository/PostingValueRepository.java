package volkovandr.hauptbuch.analytics.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL access for a Report figure's drill-down list (reporting.md §12): its postings, each
 * valued in base by the very expressions {@link ReportQueryRepository}'s turnover queries sum — so
 * the list's running total lands on its cell exactly.
 */
@Repository
public class PostingValueRepository {

  private final JdbcClient jdbcClient;

  PostingValueRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The given postings, valued by the turnover rule (data-model §6.1), in the register's own {@code
   * (date, transaction, posting)} order.
   */
  public List<PostingValue> postingValues(List<Long> postingIds, String baseCurrency) {
    if (postingIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            select p.posting_id,
                   p.transaction_id,
                   t.date,
                   a.currency_code,
                   p.amount,
                   case when not """
                + ReportQueryRepository.MISSING_RATE_EXPR
                + " then "
                + ReportQueryRepository.BASE_AMOUNT_EXPR
                + """
                end as base_amount
                from posting p
                join transaction t on t.transaction_id = p.transaction_id
                join account a on a.account_id = p.account_id
                """
                + ReportQueryRepository.RATE_LATERAL_JOIN
                + """
                where p.posting_id in (:postingIds)
                order by t.date, p.transaction_id, p.posting_id
                """)
        .param("postingIds", postingIds)
        .param("baseCurrency", baseCurrency)
        .query(PostingValue.class)
        .list();
  }
}
