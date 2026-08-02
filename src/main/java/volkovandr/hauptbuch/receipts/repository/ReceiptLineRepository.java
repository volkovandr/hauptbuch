package volkovandr.hauptbuch.receipts.repository;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.receipts.ReceiptLine;
import volkovandr.hauptbuch.receipts.ReceiptLineDraft;

/**
 * Native-SQL access to {@code receipt_line} and its {@code receipt_line_tag} junction (data-model
 * §13.2). Plain inserts / by-receipt reads and deletes — no grouping or windows — so the
 * round-trips live in the integration tier (CLAUDE.md §6). The analyse worker re-seeds
 * idempotently: {@link #deleteByReceiptId} clears any prior draft before {@link #insert} writes the
 * new one.
 */
@Repository
public class ReceiptLineRepository {

  private static final String RECEIPT_ID = "receiptId";
  private static final String RECEIPT_LINE_ID = "receiptLineId";

  private final JdbcClient jdbcClient;

  ReceiptLineRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert one draft line and return its generated id (its tags are inserted separately). */
  public long insert(long receiptId, ReceiptLineDraft draft) {
    return jdbcClient
        .sql(
            """
            insert into receipt_line
              (receipt_id, description, amount, account_id, person_id, note, sort_order,
               ai_target_text)
            values
              (:receiptId, :description, :amount, :accountId, :personId, :note, :sortOrder,
               :aiTargetText)
            returning receipt_line_id
            """)
        .param(RECEIPT_ID, receiptId)
        .param("description", draft.description())
        .param("amount", draft.amount())
        .param("accountId", draft.accountId())
        .param("personId", draft.personId())
        .param("note", draft.note())
        .param("sortOrder", draft.sortOrder())
        .param("aiTargetText", draft.aiTargetText())
        .query(Long.class)
        .single();
  }

  /** Attach a resolved leaf tag to a draft line (mirrors {@code posting_tag}, data-model §13.2). */
  public void insertTag(long receiptLineId, long tagId) {
    jdbcClient
        .sql(
            """
            insert into receipt_line_tag (receipt_line_id, tag_id)
            values (:receiptLineId, :tagId)
            on conflict (receipt_line_id, tag_id) do nothing
            """)
        .param(RECEIPT_LINE_ID, receiptLineId)
        .param("tagId", tagId)
        .update();
  }

  /** The draft lines of a receipt, in sort order — the post-process review surface's input (9f). */
  public List<ReceiptLine> findByReceiptId(long receiptId) {
    return jdbcClient
        .sql(
            """
            select receipt_line_id, receipt_id, description, amount, account_id,
                   person_id, note, sort_order, ai_target_text
            from receipt_line
            where receipt_id = :receiptId
            order by sort_order, receipt_line_id
            """)
        .param(RECEIPT_ID, receiptId)
        .query(ReceiptLine.class)
        .list();
  }

  /** The resolved tag ids attached to a draft line. */
  public List<Long> findTagIds(long receiptLineId) {
    return jdbcClient
        .sql("select tag_id from receipt_line_tag where receipt_line_id = :receiptLineId")
        .param(RECEIPT_LINE_ID, receiptLineId)
        .query(Long.class)
        .list();
  }

  /**
   * Clear a receipt's draft lines and their tags — so re-seeding a receipt (a retry after a fixed
   * config) never leaves stale lines behind.
   */
  public void deleteByReceiptId(long receiptId) {
    jdbcClient
        .sql(
            """
            delete from receipt_line_tag
            where receipt_line_id in (select receipt_line_id from receipt_line
                                      where receipt_id = :receiptId)
            """)
        .param(RECEIPT_ID, receiptId)
        .update();
    jdbcClient
        .sql("delete from receipt_line where receipt_id = :receiptId")
        .param(RECEIPT_ID, receiptId)
        .update();
  }
}
