package volkovandr.hauptbuch.importer.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.importer.ImportAccountStatistics;
import volkovandr.hauptbuch.importer.ImportCategorySignEvidence;
import volkovandr.hauptbuch.importer.ImportPayeeSummary;
import volkovandr.hauptbuch.importer.ImportStagedOpeningBalance;

/**
 * Native-SQL access to the per-account statistics (import.md §9.4; plan e′) — the verification
 * device the review page is born around. One grouped query over {@code import_file} → {@code
 * import_transaction} → {@code import_posting}: SQL-resident logic (grouping, three tables, a
 * filtered aggregate), covered in the {@code sqlLogicTest} tier (CLAUDE.md §6).
 */
@Repository
public class ImportStatisticsRepository {

  private static final String SESSION_ID = "sessionId";

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
        .param(SESSION_ID, importSessionId)
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
        .param(SESSION_ID, importSessionId)
        .query(ImportStagedOpeningBalance.class)
        .list();
  }

  /**
   * Sign evidence per Money category path in a session (import.md §5.2; plan d1) — for each path a
   * staged line references, how many of those lines carry a positive amount (a spend, in
   * Hauptbuch's sign convention) and how many a negative one (a receipt), so the category map can
   * label a path expense-vs-income at a glance. Grouped, filtered aggregate over {@code
   * import_file} → {@code import_transaction} → {@code import_posting}, scoped to category legs (a
   * non-null path): SQL-resident logic (three tables, grouping, filtered counts), covered in the
   * {@code sqlLogicTest} tier (CLAUDE.md §6). Ordered by path so the review reads in the same order
   * as the map rows.
   *
   * <p>Transactions in a state that will <strong>not</strong> book — {@code mirrored} / {@code
   * excluded} (§6) — are left out, so the hint reflects what the campaign actually commits; at d1
   * every staged row is still {@code ready}, but slice e sets those states.
   */
  public List<ImportCategorySignEvidence> perCategoryPath(long importSessionId) {
    return jdbcClient
        .sql(
            """
            select p.money_category_path                       as money_path,
                   count(*) filter (where p.amount > 0)         as debit_line_count,
                   count(*) filter (where p.amount < 0)         as credit_line_count
              from import_file f
              join import_transaction t on t.import_file_id = f.import_file_id
              join import_posting p on p.import_transaction_id = t.import_transaction_id
             where f.import_session_id = :sessionId
               and p.money_category_path is not null
               and t.state not in ('mirrored', 'excluded')
             group by p.money_category_path
             order by p.money_category_path
            """)
        .param(SESSION_ID, importSessionId)
        .query(ImportCategorySignEvidence.class)
        .list();
  }

  /**
   * The payee figures for a session's review (import.md §5.3; plan d2): the distinct payee count,
   * how many of those are seen on just one staged transaction, and how many staged rows carry a
   * wholly-destroyed name (§4.4) and so book with no payee. Payees are never mapped, so this is all
   * the review shows for them.
   *
   * <p>SQL-resident logic (grouping, case folding, filtered aggregates over {@code import_file} →
   * {@code import_transaction}), covered in the {@code sqlLogicTest} tier (CLAUDE.md §6). The
   * distinct / seen-once counts group on the case-folded {@code payee_text} with surrounding and
   * repeated whitespace normalised — an approximation of {@code PayeeService.resolveImportedPayee},
   * which additionally parses the {@code Name - City - Country} address; close enough for the
   * sanity check the owner runs against Money's own list. Rows in a non-booking state ({@code
   * mirrored} / {@code excluded}, §6) are excluded, as in {@link #perCategoryPath}.
   */
  public ImportPayeeSummary payeeResolution(long importSessionId) {
    return jdbcClient
        .sql(
            """
            with staged as (
              select t.payee_text, t.payee_destroyed
                from import_file f
                join import_transaction t on t.import_file_id = f.import_file_id
               where f.import_session_id = :sessionId
                 and t.state not in ('mirrored', 'excluded')
            ),
            named as (
              -- collapse every whitespace run to one space first, then trim the ends, so a
              -- leading tab folds the same as a leading space (the parser splits on \\s too).
              select btrim(regexp_replace(lower(payee_text), '\\s+', ' ', 'g')) as payee_key
                from staged
               where payee_text is not null
            ),
            grouped as (
              select payee_key, count(*) as sightings
                from named
               group by payee_key
            )
            select (select count(*) from grouped)                        as distinct_payees,
                   (select count(*) from grouped where sightings = 1)    as seen_once,
                   (select count(*) from staged where payee_destroyed)   as destroyed_rows
            """)
        .param(SESSION_ID, importSessionId)
        .query(ImportPayeeSummary.class)
        .single();
  }
}
