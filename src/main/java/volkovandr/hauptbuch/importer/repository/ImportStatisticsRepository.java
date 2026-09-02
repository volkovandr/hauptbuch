package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportAccountStatistics;
import volkovandr.hauptbuch.importer.ImportStagedOpeningBalance;

/**
 * Native-SQL access to the per-account statistics (import.md §9.4; plan e′) — the verification
 * device the review page is born around. One grouped query over {@code import_file} → {@code
 * import_transaction} → {@code import_posting}: SQL-resident logic (grouping, three tables, a
 * filtered aggregate), covered in the {@code sqlLogicTest} tier (CLAUDE.md §6).
 */
@Repository
public class ImportStatisticsRepository {

  private final JdbcClient jdbcClient;

  ImportStatisticsRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The count / net sum / date range per Money account in a session, ordered by account name. Files
   * staged for the same Money account name are folded together; an account referenced only as a
   * transfer target (no file of its own yet) does not appear. {@code netSum} sums only the funding
   * legs so a transfer's mirror leg in another file is not double-counted.
   */
  public List<ImportAccountStatistics> perMoneyAccount(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select f.money_account_name,
                   count(distinct t.import_transaction_id)            as transaction_count,
                   coalesce(sum(p.amount) filter (where p.funding), 0) as net_sum,
                   min(t.date)                                        as first_date,
                   max(t.date)                                        as last_date
              from import_file f
              join import_transaction t on t.import_file_id = f.import_file_id
              join import_posting p on p.import_transaction_id = t.import_transaction_id
             where f.import_session_id = :sessionId
             group by f.money_account_name
             order by f.money_account_name
            """)
        .param("sessionId", importSessionId)
        .query(ImportAccountStatistics.class)
        .list();
  }

  /**
   * Money's staged opening balance per Money account in a session (import.md §5.1; plan c3) — the
   * funding leg of every {@code import_transaction} flagged {@code opening_balance}, ordered so the
   * account map can take the earliest per account. Joins {@code import_file} → {@code
   * import_transaction} → {@code import_posting} with a filter on both the opening-balance and
   * funding flags: SQL-resident logic (three tables, a filtered join), covered in the {@code
   * sqlLogicTest} tier (CLAUDE.md §6).
   */
  public List<ImportStagedOpeningBalance> stagedOpeningBalances(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select f.money_account_name as money_account_name,
                   t.date               as date,
                   p.amount             as amount
              from import_file f
              join import_transaction t on t.import_file_id = f.import_file_id
              join import_posting p on p.import_transaction_id = t.import_transaction_id
             where f.import_session_id = :sessionId
               and t.opening_balance
               and p.funding
             order by f.money_account_name, t.date, t.import_transaction_id
            """)
        .param("sessionId", importSessionId)
        .query(ImportStagedOpeningBalance.class)
        .list();
  }
}
