package volkovandr.hauptbuch.receipts.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import volkovandr.hauptbuch.receipts.ParsedHeader;
import volkovandr.hauptbuch.receipts.Receipt;
import volkovandr.hauptbuch.receipts.ReceiptParseResult;

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

  /**
   * Save a pre-process edit (9c): record the edited image path, its edit recipe, and the AI note,
   * and move the receipt to {@code pre_processed}. Called on both first edit and re-edit (the
   * edited file is overwritten in place on disk; here the columns are simply rewritten).
   */
  public void savePreProcess(long receiptId, String editedPath, String editRecipe, String aiNote) {
    jdbcClient
        .sql(
            """
            update receipt
               set edited_path = :editedPath,
                   edit_recipe = :editRecipe,
                   ai_note = :aiNote,
                   state = 'pre_processed'
             where receipt_id = :id and deleted_at is null
            """)
        .param("editedPath", editedPath)
        .param("editRecipe", editRecipe)
        .param("aiNote", aiNote)
        .param("id", receiptId)
        .update();
  }

  /**
   * Discard a receipt's pre-process edits (9c stage-undo): clear the edited image path and recipe
   * and move back to {@code new}. The {@code ai_note} is deliberately <em>kept</em> — it describes
   * the receipt, not the pixels (receipt doc §6.1).
   */
  public void discardEdits(long receiptId) {
    jdbcClient
        .sql(
            """
            update receipt
               set edited_path = null, edit_recipe = null, state = 'new'
             where receipt_id = :id and deleted_at is null
            """)
        .param("id", receiptId)
        .update();
  }

  // ── Analyse (stage 9e): the worker's state transitions and result writes ────

  /**
   * Move a live {@code pre_processed} receipt to {@code processing} (9e): the atomic claim the
   * background worker makes before the API call. Returns the rows affected — zero when the receipt
   * is not (any longer) an un-deleted {@code pre_processed} one, so a double-submit claims nothing.
   */
  public int markProcessing(long receiptId) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'processing'
            where receipt_id = :id and state = 'pre_processed' and deleted_at is null
            """)
        .param("id", receiptId)
        .update();
  }

  /**
   * Apply a successful, decoded parse (9e): store the raw body, the usage counts, the frozen cost,
   * and the seeded header, and flip to {@code processed} — clearing any prior {@code parse_error}.
   * Scoped to a still-live receipt: a receipt soft-deleted mid-flight is left untouched (the worker
   * then abandons the result). Returns the rows affected.
   */
  public int applyProcessed(
      long receiptId, ReceiptParseResult usage, BigDecimal parseCost, ParsedHeader header) {
    return jdbcClient
        .sql(
            """
            update receipt set
              state = 'processed', parse_error = null,
              parse_raw = :parseRaw,
              tokens_in = :tokensIn, tokens_out = :tokensOut,
              tokens_cache_write = :tokensCacheWrite, tokens_cache_read = :tokensCacheRead,
              parse_cost = :parseCost,
              merchant_text = :merchantText, merchant_city = :merchantCity,
              merchant_country = :merchantCountry,
              receipt_date = :receiptDate, receipt_time = :receiptTime,
              receipt_number = :receiptNumber,
              total_amount = :totalAmount, currency_code = :currencyCode,
              account_id = :accountId
            where receipt_id = :id and deleted_at is null
            """)
        .param("id", receiptId)
        .param("parseRaw", usage.rawToon())
        .param("tokensIn", usage.tokensIn())
        .param("tokensOut", usage.tokensOut())
        .param("tokensCacheWrite", usage.tokensCacheWrite())
        .param("tokensCacheRead", usage.tokensCacheRead())
        .param("parseCost", parseCost)
        .param("merchantText", header.merchantText())
        .param("merchantCity", header.merchantCity())
        .param("merchantCountry", header.merchantCountry())
        .param("receiptDate", header.receiptDate())
        .param("receiptTime", header.receiptTime())
        .param("receiptNumber", header.receiptNumber())
        .param("totalAmount", header.totalAmount())
        .param("currencyCode", header.currencyCode())
        .param("accountId", header.accountId())
        .update();
  }

  /**
   * Fail a parse that could not complete (9e transport/API error): the reason is recorded, no
   * body/usage/cost exists to keep, and the receipt moves to {@code failed} (retryable). Scoped to
   * a live receipt.
   */
  public int failTransport(long receiptId, String parseError) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'failed', parse_error = :parseError
            where receipt_id = :id and deleted_at is null
            """)
        .param("id", receiptId)
        .param("parseError", parseError)
        .update();
  }

  /**
   * Fail a parse whose body came back but could not be decoded (9e): the raw body <em>is</em> kept
   * (audit) along with its usage and computed cost, the reason is recorded, and the receipt moves
   * to {@code failed}. Scoped to a live receipt.
   */
  public int failUndecodable(
      long receiptId, String parseError, ReceiptParseResult usage, BigDecimal parseCost) {
    return jdbcClient
        .sql(
            """
            update receipt set
              state = 'failed', parse_error = :parseError,
              parse_raw = :parseRaw,
              tokens_in = :tokensIn, tokens_out = :tokensOut,
              tokens_cache_write = :tokensCacheWrite, tokens_cache_read = :tokensCacheRead,
              parse_cost = :parseCost
            where receipt_id = :id and deleted_at is null
            """)
        .param("id", receiptId)
        .param("parseError", parseError)
        .param("parseRaw", usage.rawToon())
        .param("tokensIn", usage.tokensIn())
        .param("tokensOut", usage.tokensOut())
        .param("tokensCacheWrite", usage.tokensCacheWrite())
        .param("tokensCacheRead", usage.tokensCacheRead())
        .param("parseCost", parseCost)
        .update();
  }

  /**
   * Retry a {@code failed} receipt (9e): back to {@code pre_processed} with the error cleared,
   * ready to Analyse again. Returns the rows affected (zero when the receipt is not a live failed
   * one).
   */
  public int retryToPreProcessed(long receiptId) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'pre_processed', parse_error = null
            where receipt_id = :id and state = 'failed' and deleted_at is null
            """)
        .param("id", receiptId)
        .update();
  }

  /**
   * The startup sweep (9e): flip every orphaned single-mode {@code processing} receipt (no {@code
   * batch_id}, not soft-deleted) to {@code failed} — its worker thread died with the JVM. Batch
   * rows are exempt (9h's poller resumes them). Returns how many were swept.
   */
  public int sweepOrphanedProcessing(String parseError) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'failed', parse_error = :parseError
            where state = 'processing' and batch_id is null and deleted_at is null
            """)
        .param("parseError", parseError)
        .update();
  }
}
