package volkovandr.hauptbuch.receipts.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.receipts.Receipt;

/**
 * Repository for {@link Receipt} rows (data-model §13.1). All reads scope to {@code deleted_at is
 * null} (live receipts) — soft-deleted rows never surface in a list or an image serve.
 *
 * <p>The register and mobile queries are plain filtered selects over the single {@code receipt}
 * table (a state {@code in}-list, a capture-time window, an order) — no joins, grouping, or windows
 * — so they live in the integration round-trip tier per CLAUDE.md §6, not {@code sqlLogicTest}.
 */
@Repository
public class ReceiptRepository {

  private final JdbcClient jdbcClient;

  ReceiptRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** Insert a freshly captured {@code new} receipt with its stored original path. */
  public Receipt insertCaptured(String source, String originalPath) {
    return jdbcClient
        .sql(
            """
            insert into receipt (state, source, original_path)
            values ('new', :source, :originalPath)
            returning *
            """)
        .param("source", source)
        .param("originalPath", originalPath)
        .query(Receipt.class)
        .single();
  }

  /** Fetch a live receipt by id; empty if soft-deleted or absent. */
  public Optional<Receipt> findById(long receiptId) {
    return jdbcClient
        .sql("select * from receipt where receipt_id = :id and deleted_at is null")
        .param("id", receiptId)
        .query(Receipt.class)
        .optional();
  }

  /**
   * The PC register list (§5): live receipts whose state is in {@code states} and captured on or
   * after {@code from}, oldest capture first — natural backlog order. A null {@code from} is
   * open-ended (the "everything" date-range option, §9b). The register's date filter is preset
   * lower-bounds only (last 90 days / last year / everything); an explicit upper bound arrives with
   * a real caller for it.
   */
  public List<Receipt> findForRegister(List<String> states, LocalDate from) {
    String sql =
        "select * from receipt where deleted_at is null and state in (:states)"
            + (from == null ? "" : " and captured_at >= :from")
            + " order by captured_at asc, receipt_id asc";

    JdbcClient.StatementSpec spec = jdbcClient.sql(sql).param("states", states);
    if (from != null) {
      spec = spec.param("from", from.atStartOfDay());
    }
    return spec.query(Receipt.class).list();
  }

  /**
   * The mobile grid (§4): every live receipt captured on or after {@code since}, newest capture
   * first, all states including {@code committed} (§9b extends §4). The 90-day floor keeps the grid
   * bounded.
   */
  public List<Receipt> findForMobile(OffsetDateTime since) {
    return jdbcClient
        .sql(
            """
            select * from receipt
            where deleted_at is null and captured_at >= :since
            order by captured_at desc, receipt_id desc
            """)
        .param("since", since)
        .query(Receipt.class)
        .list();
  }

  /**
   * The live receipts among {@code ids}, for the context menu / bulk actions (§5.2) — the raw
   * material for deciding which actions apply to a selection and skipping invalid members. Empty
   * {@code ids} short-circuits to an empty list (an {@code in ()} is invalid SQL).
   */
  public List<Receipt> findLiveByIds(List<Long> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql("select * from receipt where receipt_id in (:ids) and deleted_at is null")
        .param("ids", ids)
        .query(Receipt.class)
        .list();
  }

  /** Soft-delete a receipt (reversible; the row stays, {@code deleted_at} is stamped). */
  public void softDelete(long receiptId) {
    jdbcClient
        .sql("update receipt set deleted_at = now() where receipt_id = :id")
        .param("id", receiptId)
        .update();
  }

  /** Move a live receipt to a new lifecycle state (e.g. discard → {@code discarded}). */
  public void updateState(long receiptId, String state) {
    jdbcClient
        .sql("update receipt set state = :state where receipt_id = :id and deleted_at is null")
        .param("state", state)
        .param("id", receiptId)
        .update();
  }
}
