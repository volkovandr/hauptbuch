package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.receipts.repository.ReceiptLineRepository;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * The transactional writes of the analyse step (stage 9e), kept off the {@link ReceiptAnalyser}
 * worker so the network call never runs inside a DB transaction and so the worker's cross-method
 * calls cross a proxy boundary. Each method is one short transaction: claim, apply a result, or
 * fail.
 *
 * <p>Soft-delete tolerance (9b's delete ladder, data-model §13.1): the result writes are scoped to
 * a still-live receipt, so a receipt deleted mid-flight is left untouched and its seeded lines are
 * not written — the worker simply abandons the outcome.
 */
@Service
@Transactional
public class ReceiptAnalysisService {

  private final ReceiptRepository receiptRepository;
  private final ReceiptLineRepository receiptLineRepository;

  ReceiptAnalysisService(
      ReceiptRepository receiptRepository, ReceiptLineRepository receiptLineRepository) {
    this.receiptRepository = receiptRepository;
    this.receiptLineRepository = receiptLineRepository;
  }

  /** Claim a {@code pre_processed} receipt for analysis; false when it is not (any longer) one. */
  public boolean claim(long receiptId) {
    return receiptRepository.markProcessing(receiptId) > 0;
  }

  /**
   * Persist a successful, decoded parse: the header + telemetry on the receipt (→ {@code
   * processed}), then the seeded draft lines and their tags. A receipt soft-deleted mid-flight
   * fails the header update (zero rows) and its lines are not written.
   */
  public void applyProcessed(
      long receiptId, ReceiptParseResult usage, BigDecimal cost, SeededReceipt seeded) {
    int updated = receiptRepository.applyProcessed(receiptId, usage, cost, seeded.header());
    if (updated == 0) {
      return; // deleted mid-flight — abandon the result (data-model §13.1)
    }
    receiptLineRepository.deleteByReceiptId(receiptId);
    for (ReceiptLineDraft line : seeded.lines()) {
      long lineId = receiptLineRepository.insert(receiptId, line);
      for (Long tagId : line.tagIds()) {
        receiptLineRepository.insertTag(lineId, tagId);
      }
    }
  }

  /** Fail a parse that could not complete (transport/API error) — no body/usage to keep. */
  public void failTransport(long receiptId, String parseError) {
    receiptRepository.failTransport(receiptId, parseError);
  }

  /** Fail a parse whose body came back but could not be decoded — the raw body + usage are kept. */
  public void failUndecodable(
      long receiptId, String parseError, ReceiptParseResult usage, BigDecimal cost) {
    receiptRepository.failUndecodable(receiptId, parseError, usage, cost);
  }

  /**
   * Retry a failed receipt back to {@code pre_processed}; false when it is not a live failed one.
   */
  public boolean retry(long receiptId) {
    return receiptRepository.retryToPreProcessed(receiptId) > 0;
  }

  /** The startup sweep of orphaned single-mode {@code processing} rows (data-model §13.1). */
  public int sweepOrphans(String parseError) {
    return receiptRepository.sweepOrphanedProcessing(parseError);
  }
}
