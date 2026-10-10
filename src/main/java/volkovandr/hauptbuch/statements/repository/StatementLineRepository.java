package volkovandr.hauptbuch.statements.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.statements.StatementLine;

/**
 * Native-SQL access to {@code statement_line} (data-model §15). Plain inserts, updates and reads by
 * statement, so the round-trips live in the integration tier (CLAUDE.md §6).
 */
@Repository
public class StatementLineRepository {

  private static final String STATEMENT_ID = "statementId";

  private final JdbcClient jdbcClient;

  StatementLineRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert {@code line} under {@code statementId} and return its generated id. */
  public long insert(long statementId, StatementLine line) {
    return jdbcClient
        .sql(
            """
            insert into statement_line
              (statement_id, sort_order, booking_date, value_date, amount, counterparty,
               description, bank_category, raw_text, problem)
            values
              (:statementId, :sortOrder, :bookingDate, :valueDate, :amount, :counterparty,
               :description, :bankCategory, :rawText, :problem)
            returning statement_line_id
            """)
        .param(STATEMENT_ID, statementId)
        .param("sortOrder", line.sortOrder())
        .param("bookingDate", line.bookingDate())
        .param("valueDate", line.valueDate())
        .param("amount", line.amount())
        .param("counterparty", line.counterparty())
        .param("description", line.description())
        .param("bankCategory", line.bankCategory())
        .param("rawText", line.rawText())
        .param("problem", line.problem())
        .query(Long.class)
        .single();
  }

  /**
   * Insert a line the AI read, with the foreign charge the bank printed. An original currency the
   * book does not know is dropped and the line flagged with a problem, rather than failing the
   * whole parse.
   */
  public long insertParsed(
      long statementId,
      StatementLine line,
      BigDecimal originalAmount,
      String originalCurrency,
      BigDecimal originalRate) {
    return jdbcClient
        .sql(
            """
            insert into statement_line
              (statement_id, sort_order, booking_date, value_date, amount, counterparty,
               description, bank_category, raw_text, problem,
               original_amount, original_currency_code, original_rate)
            select :statementId, :sortOrder, :bookingDate, :valueDate, :amount, :counterparty,
                   :description, :bankCategory, :rawText,
                   coalesce(cast(:problem as text),
                            case when given.code is not null and c.currency_code is null
                                 then 'The original currency ' || given.code || ' is not known.'
                            end),
                   :originalAmount, c.currency_code, :originalRate
            from (select cast(:originalCurrency as text) as code) given
            left join currency c on c.currency_code = given.code
            returning statement_line_id
            """)
        .param(STATEMENT_ID, statementId)
        .param("sortOrder", line.sortOrder())
        .param("bookingDate", line.bookingDate())
        .param("valueDate", line.valueDate())
        .param("amount", line.amount())
        .param("counterparty", line.counterparty())
        .param("description", line.description())
        .param("bankCategory", line.bankCategory())
        .param("rawText", line.rawText())
        .param("problem", line.problem())
        .param("originalAmount", originalAmount)
        .param("originalCurrency", originalCurrency)
        .param("originalRate", originalRate)
        .query(Long.class)
        .single();
  }

  /** Flip the sign of every line's amount and foreign amount (the bank prints them reversed). */
  public int negateAmounts(long statementId) {
    return jdbcClient
        .sql(
            """
            update statement_line
            set amount = -amount, original_amount = -original_amount
            where statement_id = :statementId
            """)
        .param(STATEMENT_ID, statementId)
        .update();
  }

  /** Delete every line of the statement (a parse replaces them). */
  public int deleteByStatement(long statementId) {
    return jdbcClient
        .sql("delete from statement_line where statement_id = :statementId")
        .param(STATEMENT_ID, statementId)
        .update();
  }

  /** The foreign charge printed on the line, when it has both an amount and a known currency. */
  public Optional<OriginalCharge> findOriginalCharge(long statementLineId) {
    return jdbcClient
        .sql(
            """
            select original_amount, original_currency_code
            from statement_line
            where statement_line_id = :lineId
              and original_amount is not null and original_currency_code is not null
            """)
        .param("lineId", statementLineId)
        .query((rs, rowNum) -> new OriginalCharge(rs.getBigDecimal(1), rs.getString(2)))
        .optional();
  }

  /** The statement's lines in file order. */
  public List<StatementLine> findByStatement(long statementId) {
    return jdbcClient
        .sql(
            """
            select statement_line_id, sort_order, booking_date, value_date, amount, counterparty,
                   description, bank_category, raw_text, problem
            from statement_line
            where statement_id = :statementId
            order by sort_order, statement_line_id
            """)
        .param(STATEMENT_ID, statementId)
        .query(StatementLine.class)
        .list();
  }

  /**
   * Overwrite a line's editable fields (the grid, statements.md §6.1); the source row and the
   * position are kept.
   *
   * @return the number of lines updated (0 when the id is unknown or not on the statement)
   */
  public int update(long statementId, StatementLine line) {
    return jdbcClient
        .sql(
            """
            update statement_line
            set booking_date = :bookingDate, value_date = :valueDate, amount = :amount,
                counterparty = :counterparty, description = :description,
                bank_category = :bankCategory, problem = :problem
            where statement_line_id = :statementLineId and statement_id = :statementId
            """)
        .param(STATEMENT_ID, statementId)
        .param("statementLineId", line.statementLineId())
        .param("bookingDate", line.bookingDate())
        .param("valueDate", line.valueDate())
        .param("amount", line.amount())
        .param("counterparty", line.counterparty())
        .param("description", line.description())
        .param("bankCategory", line.bankCategory())
        .param("problem", line.problem())
        .update();
  }
}
