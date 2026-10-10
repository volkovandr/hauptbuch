package volkovandr.hauptbuch.statements.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.statements.Statement;
import volkovandr.hauptbuch.statements.StatementRow;

/**
 * Native-SQL access to {@code statement} (data-model §15). The list query joins the account, the
 * profile and a count of the lines, so it is exercised with its own scenarios in the SQL-logic tier
 * (CLAUDE.md §6); the rest are plain round-trips.
 */
@Repository
public class StatementRepository {

  private static final String STATEMENT_ID = "statementId";

  private final JdbcClient jdbcClient;

  StatementRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a {@code processed} statement and return its generated id. */
  public long insert(
      long statementProfileId,
      long accountId,
      String originalFilename,
      String filePath,
      LocalDate periodStart,
      LocalDate periodEnd) {
    return jdbcClient
        .sql(
            """
            insert into statement
              (statement_profile_id, account_id, state, original_filename, file_path,
               period_start, period_end)
            values
              (:statementProfileId, :accountId, 'processed', :originalFilename, :filePath,
               :periodStart, :periodEnd)
            returning statement_id
            """)
        .param("statementProfileId", statementProfileId)
        .param("accountId", accountId)
        .param("originalFilename", originalFilename)
        .param("filePath", filePath)
        .param("periodStart", periodStart)
        .param("periodEnd", periodEnd)
        .query(Long.class)
        .single();
  }

  /**
   * Insert a {@code new} PDF statement — its text extracted, masked and waiting for the operator —
   * and return its generated id (statements.md §3.2).
   */
  public long insertPdf(
      long statementProfileId,
      long accountId,
      String originalFilename,
      String filePath,
      String sentText) {
    return jdbcClient
        .sql(
            """
            insert into statement
              (statement_profile_id, account_id, state, original_filename, file_path, sent_text)
            values
              (:statementProfileId, :accountId, 'new', :originalFilename, :filePath, :sentText)
            returning statement_id
            """)
        .param("statementProfileId", statementProfileId)
        .param("accountId", accountId)
        .param("originalFilename", originalFilename)
        .param("filePath", filePath)
        .param("sentText", sentText)
        .query(Long.class)
        .single();
  }

  /** The text of a PDF statement as it stands, or empty for a CSV statement. */
  public Optional<String> findSentText(long statementId) {
    return jdbcClient
        .sql("select sent_text from statement where statement_id = :statementId")
        .param(STATEMENT_ID, statementId)
        .query(String.class)
        .optional();
  }

  /**
   * Overwrite the text of a live PDF statement that has not been parsed yet (state {@code new} or
   * {@code failed}).
   *
   * @return the number of statements updated
   */
  public int updateSentText(long statementId, String sentText) {
    return jdbcClient
        .sql(
            """
            update statement set sent_text = :sentText, updated_at = now()
            where statement_id = :statementId and deleted_at is null
              and sent_text is not null and state in ('new', 'failed')
            """)
        .param(STATEMENT_ID, statementId)
        .param("sentText", sentText)
        .update();
  }

  /**
   * Claim a live PDF statement for parsing ({@code new} or {@code failed} to {@code processing}),
   * clearing the previous error. Atomic, so a double click parses once.
   *
   * @return true when the statement was claimed
   */
  public boolean claimForParse(long statementId) {
    return jdbcClient
            .sql(
                """
                update statement set state = 'processing', parse_error = null, updated_at = now()
                where statement_id = :statementId and deleted_at is null
                  and sent_text is not null and state in ('new', 'failed')
                """)
            .param(STATEMENT_ID, statementId)
            .update()
        > 0;
  }

  /** Land a parse that could not complete: {@code failed} with the reason, no usage recorded. */
  public void markFailed(long statementId, String parseError) {
    jdbcClient
        .sql(
            """
            update statement set state = 'failed', parse_error = :parseError, updated_at = now()
            where statement_id = :statementId
            """)
        .param(STATEMENT_ID, statementId)
        .param("parseError", parseError)
        .update();
  }

  /** Land a parse whose body would not decode: {@code failed}, keeping the raw body and usage. */
  public void markFailedWithResult(
      long statementId, String parseError, ParseUsage usage, String parseRaw) {
    jdbcClient
        .sql(
            """
            update statement
            set state = 'failed', parse_error = :parseError, parse_raw = :parseRaw,
                tokens_in = :tokensIn, tokens_out = :tokensOut,
                tokens_cache_write = :tokensCacheWrite, tokens_cache_read = :tokensCacheRead,
                parse_cost = :parseCost, updated_at = now()
            where statement_id = :statementId
            """)
        .param(STATEMENT_ID, statementId)
        .param("parseError", parseError)
        .param("parseRaw", parseRaw)
        .params(usage.asParams())
        .update();
  }

  /** Land a decoded parse: {@code processed} with the header, the raw body and the usage. */
  public void markProcessed(
      long statementId,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      ParseUsage usage,
      String parseRaw) {
    jdbcClient
        .sql(
            """
            update statement
            set state = 'processed', parse_error = null, parse_raw = :parseRaw,
                period_start = :periodStart, period_end = :periodEnd,
                opening_balance = :openingBalance, closing_balance = :closingBalance,
                tokens_in = :tokensIn, tokens_out = :tokensOut,
                tokens_cache_write = :tokensCacheWrite, tokens_cache_read = :tokensCacheRead,
                parse_cost = :parseCost, updated_at = now()
            where statement_id = :statementId
            """)
        .param(STATEMENT_ID, statementId)
        .param("parseRaw", parseRaw)
        .param("periodStart", periodStart)
        .param("periodEnd", periodEnd)
        .param("openingBalance", openingBalance)
        .param("closingBalance", closingBalance)
        .params(usage.asParams())
        .update();
  }

  /** Fail every statement left {@code processing} by a restart; returns how many. */
  public int failOrphanedProcessing(String parseError) {
    return jdbcClient
        .sql(
            """
            update statement set state = 'failed', parse_error = :parseError, updated_at = now()
            where state = 'processing'
            """)
        .param("parseError", parseError)
        .update();
  }

  /** The reason the last parse failed, or empty when it did not (or never ran). */
  public Optional<String> findParseError(long statementId) {
    return jdbcClient
        .sql("select parse_error from statement where statement_id = :statementId")
        .param(STATEMENT_ID, statementId)
        .query(String.class)
        .optional();
  }

  /** A statement by id, live or soft-deleted. */
  public Optional<Statement> findById(long statementId) {
    return jdbcClient
        .sql(
            """
            select statement_id, statement_profile_id, account_id, state, original_filename,
                   file_path, period_start, period_end, opening_balance, closing_balance,
                   created_at, deleted_at
            from statement
            where statement_id = :statementId
            """)
        .param(STATEMENT_ID, statementId)
        .query(Statement.class)
        .optional();
  }

  /**
   * Overwrite a live statement's period and balances (statements.md §3.4).
   *
   * @return the number of statements updated (0 when the id is unknown or soft-deleted)
   */
  public int updateHeader(
      long statementId,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal openingBalance,
      BigDecimal closingBalance) {
    return jdbcClient
        .sql(
            """
            update statement
            set period_start = :periodStart, period_end = :periodEnd,
                opening_balance = :openingBalance, closing_balance = :closingBalance,
                updated_at = now()
            where statement_id = :statementId and deleted_at is null
            """)
        .param(STATEMENT_ID, statementId)
        .param("periodStart", periodStart)
        .param("periodEnd", periodEnd)
        .param("openingBalance", openingBalance)
        .param("closingBalance", closingBalance)
        .update();
  }

  /**
   * Soft-delete a live statement.
   *
   * @return the number of statements deleted (0 when the id is unknown or already deleted)
   */
  public int softDelete(long statementId) {
    return jdbcClient
        .sql(
            """
            update statement set deleted_at = now(), updated_at = now()
            where statement_id = :statementId and deleted_at is null
            """)
        .param(STATEMENT_ID, statementId)
        .update();
  }

  /**
   * The live statements for the list, newest first (by period end, then upload); {@code accountId}
   * null lists every account.
   */
  public List<StatementRow> findLiveRows(Long accountId) {
    return jdbcClient
        .sql(
            """
            select s.statement_id, a.name as account_name, p.name as profile_name,
                   s.original_filename, s.period_start, s.period_end,
                   count(l.statement_line_id) as line_count,
                   count(l.problem) as problem_count
            from statement s
            join account a on a.account_id = s.account_id
            join statement_profile p on p.statement_profile_id = s.statement_profile_id
            left join statement_line l on l.statement_id = s.statement_id
            where s.deleted_at is null
              and (cast(:accountId as bigint) is null or s.account_id = :accountId)
            group by s.statement_id, a.name, p.name
            order by s.period_end desc nulls last, s.statement_id desc
            """)
        .param("accountId", accountId)
        .query(StatementRow.class)
        .list();
  }
}
