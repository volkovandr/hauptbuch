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
import volkovandr.hauptbuch.receipts.ReceiptHeaderDraft;
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

  /** The bind name shared by every "fail this receipt with a reason" update. */
  private static final String PARSE_ERROR = "parseError";

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
   * How many live receipts have been analyzed — a parse response is stored ({@code parse_raw is not
   * null}), which is true for {@code processed} and {@code committed} receipts and robust to a
   * reopened one. Excludes {@code new}, {@code pre_processed} and transport-failed receipts. Feeds
   * the landing-page tracking-stats line (CONTEXT.md "Tracking stats").
   */
  public long countAnalyzed() {
    return jdbcClient
        .sql("select count(*) from receipt where deleted_at is null and parse_raw is not null")
        .query(Long.class)
        .single();
  }

  /**
   * The PC register list (§5): live receipts whose state is in {@code states} and captured on or
   * after {@code from}. A null {@code from} is open-ended (the "everything" date-range option,
   * §9b). The register's date filter is preset lower-bounds only (last 90 days / last year /
   * everything); an explicit upper bound arrives with a real caller for it.
   *
   * <p>Ordered by {@code total_amount} when {@code sortByTotal} is true, else by {@code
   * captured_at} (today's default) — the two real columns the register's sortable-header feature
   * (issue tracker #11) can order in SQL; the other two sortable columns aren't columns at all and
   * sort in memory afterward (the {@code receipts} module's {@code ReceiptSort.sortByLookup}), so
   * any caller ordering by one of those simply leaves {@code sortByTotal} false — the base order
   * this method picks for them is immaterial, since it's fully overridden downstream. Either way,
   * {@code descending} sets the direction, with {@code captured_at} then {@code receipt_id} as the
   * tiebreak (both in the same direction) — for the default {@code captured_at} order, that
   * collapses to {@code receipt_id} alone. A null {@code total_amount} (unparsed) places last
   * ascending, first descending — the standard SQL convention, made explicit here.
   */
  public List<Receipt> findForRegister(
      List<String> states, LocalDate from, boolean sortByTotal, boolean descending) {
    String dir = descending ? "desc" : "asc";
    String orderBy =
        sortByTotal
            ? "total_amount "
                + dir
                + (descending ? " nulls first" : " nulls last")
                + ", "
                + "captured_at "
                + dir
                + ", receipt_id "
                + dir
            : "captured_at " + dir + ", receipt_id " + dir;
    String sql =
        "select * from receipt where deleted_at is null and state in (:states)"
            + (from == null ? "" : " and captured_at >= :from")
            + " order by "
            + orderBy;

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

  /**
   * Persist the post-process header edits (9f): the operator's date, payee, paying account, header
   * currency, (editable) total, and — from 9g — the header note and receipt number. The receipt
   * stays {@code processed} — Save reviews the draft, it does not advance the state ({@code
   * committed} is 9g's Confirm). Scoped to a live receipt.
   */
  public void saveEditorHeader(long receiptId, ReceiptHeaderDraft header) {
    jdbcClient
        .sql(
            """
            update receipt
               set receipt_date = :receiptDate,
                   payee_id = :payeeId,
                   account_id = :accountId,
                   currency_code = :currencyCode,
                   total_amount = :totalAmount,
                   note = :note,
                   receipt_number = :receiptNumber,
                   funding_total = :fundingTotal,
                   base_total = :baseTotal
             where receipt_id = :id and deleted_at is null
            """)
        .param("receiptDate", header.receiptDate())
        .param("payeeId", header.payeeId())
        .param("accountId", header.accountId())
        .param("currencyCode", header.currencyCode())
        .param("totalAmount", header.totalAmount())
        .param("note", header.note())
        .param("receiptNumber", header.receiptNumber())
        .param("fundingTotal", header.fundingTotal())
        .param("baseTotal", header.baseTotal())
        .param("id", receiptId)
        .update();
  }

  /**
   * Book a reviewed draft (9g Confirm): link the receipt to the transaction it materialised and
   * flip it to {@code committed}. Scoped to a live receipt; returns the rows affected.
   */
  public int markCommitted(long receiptId, long transactionId) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'committed', transaction_id = :transactionId
            where receipt_id = :id and deleted_at is null
            """)
        .param("transactionId", transactionId)
        .param("id", receiptId)
        .update();
  }

  /**
   * Reopen a {@code committed} receipt (9g): back to {@code processed} for another round of
   * editing. Nothing else is written — the transaction is untouched and the {@code transaction_id}
   * link is <em>kept</em>, which is what later makes the confirm button read "Re-enter". Returns
   * the rows affected (zero when the receipt is not a live committed one).
   */
  public int reopen(long receiptId) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'processed'
            where receipt_id = :id and state = 'committed' and deleted_at is null
            """)
        .param("id", receiptId)
        .update();
  }

  // ── Analyse (stage 9e): the worker's state transitions and result writes ────

  /**
   * Move a live {@code pre_processed} receipt to {@code processing} (9e): the atomic claim the
   * background worker makes before the API call. Returns the rows affected — zero when the receipt
   * is not (any longer) an un-deleted {@code pre_processed} one, so a double-submit claims nothing.
   *
   * <p>The claim <em>clears</em> any {@code batch_id} a previous round left behind (9h). Every
   * claim starts out single-mode; the batch path re-stamps its id immediately after the create call
   * returns. Without this a retried batch member would keep pointing at its dead batch — exempting
   * it from the startup sweep and luring the poller into failing a perfectly live single parse.
   */
  public int markProcessing(long receiptId) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'processing', batch_id = null
            where receipt_id = :id and state = 'pre_processed' and deleted_at is null
            """)
        .param("id", receiptId)
        .update();
  }

  /**
   * Apply a successful, decoded parse (9e): store the raw body, the usage counts, the frozen cost,
   * and the seeded header, and flip to {@code processed} — clearing any prior {@code parse_error}.
   * Scoped to a still-live, not-yet-{@code committed} receipt: one soft-deleted mid-flight, or
   * committed between the caller's state check and this write (the operator's re-seed racing
   * Confirm, receipt-processing/19), is left untouched — its lines back a booked transaction and
   * the caller then abandons the result. Returns the rows affected.
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
            where receipt_id = :id and state <> 'committed' and deleted_at is null
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
        .param(PARSE_ERROR, parseError)
        .update();
  }

  /**
   * Fail a parse whose body came back but could not be decoded (9e): the raw body <em>is</em> kept
   * (audit) along with its usage and computed cost, the reason is recorded, and the receipt moves
   * to {@code failed}. Scoped to a live, not-yet-{@code committed} receipt, for the same reason
   * {@link #applyProcessed} is: a committed receipt's draft is the record of what was booked.
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
            where receipt_id = :id and state <> 'committed' and deleted_at is null
            """)
        .param("id", receiptId)
        .param(PARSE_ERROR, parseError)
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
   *
   * <p>The {@code batch_id} goes too (9h): a retried member has left that batch, so it should stop
   * carrying the register's batch badge and stop keeping a finished batch on the poller's list.
   */
  public int retryToPreProcessed(long receiptId) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'pre_processed', parse_error = null, batch_id = null
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
        .param(PARSE_ERROR, parseError)
        .update();
  }

  // ── Batch (stage 9h): membership and the poller's lookups ───────────────────

  /**
   * Stamp the Batches-API id on every member of a just-created batch (9h). Written immediately
   * after the create call returns, so a restart finds the job again — the poller resumes it and the
   * 9e startup sweep leaves it alone. Empty {@code ids} short-circuits (an {@code in ()} is invalid
   * SQL).
   */
  public int assignBatch(List<Long> ids, String batchId) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbcClient
        .sql(
            """
            update receipt set batch_id = :batchId
            where receipt_id in (:ids) and deleted_at is null
            """)
        .param("batchId", batchId)
        .param("ids", ids)
        .update();
  }

  /**
   * The distinct ids of every batch that still has a live {@code processing} member (9h) — what the
   * poller ticks over, and what makes it idle (an empty list) when nothing is in flight. Multiple
   * concurrent batches need no special handling: each id is polled once.
   */
  public List<String> findLiveBatchIds() {
    return jdbcClient
        .sql(
            """
            select distinct batch_id from receipt
            where state = 'processing' and batch_id is not null and deleted_at is null
            """)
        .query(String.class)
        .list();
  }

  /**
   * The live, still-{@code processing} members of a batch (9h): who a distributed result may be
   * applied to, and — once the results are in — who is left without one to fail. A member
   * soft-deleted mid-flight is absent, so its result is quietly abandoned (9e tolerance).
   */
  public List<Receipt> findLiveBatchMembers(String batchId) {
    return jdbcClient
        .sql(
            """
            select * from receipt
            where batch_id = :batchId and state = 'processing' and deleted_at is null
            order by receipt_id asc
            """)
        .param("batchId", batchId)
        .query(Receipt.class)
        .list();
  }

  /**
   * Fail the claimed receipts of a batch that never reached the API (9h) — the submit call itself
   * threw, so there is no {@code batch_id} to key on yet. The standard Retry path applies from
   * there. Empty {@code ids} short-circuits.
   */
  public int failClaimed(List<Long> ids, String parseError) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbcClient
        .sql(
            """
            update receipt set state = 'failed', parse_error = :parseError
            where receipt_id in (:ids) and state = 'processing' and deleted_at is null
            """)
        .param(PARSE_ERROR, parseError)
        .param("ids", ids)
        .update();
  }

  /**
   * Fail every live member of a batch still sitting in {@code processing} (9h): the whole batch on
   * a poll failure (a 404'd batch), or the leftovers after an ended batch returned no result for
   * them. Returns how many were failed.
   */
  public int failBatchMembers(String batchId, String parseError) {
    return jdbcClient
        .sql(
            """
            update receipt set state = 'failed', parse_error = :parseError
            where batch_id = :batchId and state = 'processing' and deleted_at is null
            """)
        .param("batchId", batchId)
        .param(PARSE_ERROR, parseError)
        .update();
  }
}
