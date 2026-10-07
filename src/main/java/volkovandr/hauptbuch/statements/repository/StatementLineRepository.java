package volkovandr.hauptbuch.statements.repository;

import java.util.List;
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

  /** Whether the line has a confirmed match. */
  public boolean isMatched(long statementLineId) {
    return jdbcClient
        .sql("select exists (select 1 from statement_match where statement_line_id = :lineId)")
        .param("lineId", statementLineId)
        .query(Boolean.class)
        .single();
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
