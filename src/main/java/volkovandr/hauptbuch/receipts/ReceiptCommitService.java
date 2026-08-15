package volkovandr.hauptbuch.receipts;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * The committed end of the receipt lifecycle (plan §9g): Confirm, Reopen, Re-enter, and the
 * transaction-aware delete — the last rung of 9b's delete ladder.
 *
 * <p>Confirm is where the draft stops being a draft: the gate hard-blocks anything the ledger would
 * choke on ({@link ReceiptConfirmGate}), Save persists the reviewed draft one last time so {@code
 * receipt_line} is exactly what got booked (the audit chain of data-model §13.2 — {@code parse_raw}
 * → {@code receipt_line} → postings), and the whole thing goes through {@code DockSplitService},
 * the one commit path in the app.
 *
 * <p><strong>Re-entry</strong> (a Confirm on a receipt that has already been booked and reopened)
 * voids the predecessor and books afresh, deliberately overwriting any register-side hand-edits —
 * the settled no-drift-check. The voided transaction stays soft-deleted and inspectable; the
 * receipt simply points at the new one. The predecessor may already be voided — e.g. voided
 * directly from the register, bypassing receipts entirely (issue tracker #08) — in which case
 * voiding it again is a no-op, not an error: {@link DockCommitService#voidTransactionIfLive} is
 * used here (and on the committed-delete dialog's void axis below) rather than the strict {@code
 * voidTransaction} the register's own edit-mode void uses.
 *
 * <p>Lives in {@code receipts} beside the rest of the receipt lifecycle, calling {@code operations}
 * for the two ledger-side operations (commit, void) exactly as the plan's boundary note anticipates
 * — {@code receipts → operations} is an edge the module verification already carries.
 */
@Service
@Transactional
public class ReceiptCommitService {

  private final ReceiptService receiptService;
  private final ReceiptEditorService receiptEditorService;
  private final ReceiptConfirmGate receiptConfirmGate;
  private final ReceiptRepository receiptRepository;
  private final DockSplitService dockSplitService;
  private final DockCommitService dockCommitService;

  ReceiptCommitService(
      ReceiptService receiptService,
      ReceiptEditorService receiptEditorService,
      ReceiptConfirmGate receiptConfirmGate,
      ReceiptRepository receiptRepository,
      DockSplitService dockSplitService,
      DockCommitService dockCommitService) {
    this.receiptService = receiptService;
    this.receiptEditorService = receiptEditorService;
    this.receiptConfirmGate = receiptConfirmGate;
    this.receiptRepository = receiptRepository;
    this.dockSplitService = dockSplitService;
    this.dockCommitService = dockCommitService;
  }

  /**
   * Book the reviewed draft: gate it, persist it, materialise it through the split commit path, and
   * flip the receipt to {@code committed} pointing at the transaction. A receipt that already
   * carries a transaction (reopened, now re-entered) has that one voided first.
   *
   * @return the id of the transaction the receipt now backs
   * @throws ReceiptConfirmException if any hard block stands (the receipt is left untouched)
   * @throws IllegalStateException if the receipt is not a live {@code processed} one
   * @throws IllegalArgumentException if the receipt does not exist
   */
  public long confirm(long receiptId, ReceiptEditorForm form) {
    Receipt receipt = requireProcessed(receiptId);
    List<String> problems = receiptConfirmGate.problems(form, receiptEditorService.panel(form));
    if (!problems.isEmpty()) {
      throw new ReceiptConfirmException(problems);
    }

    receiptEditorService.save(receiptId, form);
    Receipt saved = receiptService.findById(receiptId).orElseThrow(() -> vanished(receiptId));
    if (receipt.transactionId() != null) {
      dockCommitService.voidTransactionIfLive(receipt.transactionId());
    }
    long transactionId = dockSplitService.commit(ReceiptSplitEntries.of(saved, form));
    receiptRepository.markCommitted(receiptId, transactionId);
    return transactionId;
  }

  /**
   * Reopen a committed receipt for another round of editing (plan §9g): instant, no dialog, nothing
   * written but the state — the transaction is untouched and stays linked, which is what makes the
   * next Confirm a Re-enter.
   *
   * @throws IllegalStateException if the receipt is not {@code committed}
   * @throws IllegalArgumentException if the receipt does not exist
   */
  public void reopen(long receiptId) {
    requireCommitted(receiptId);
    receiptRepository.reopen(receiptId);
  }

  /**
   * The committed delete (plan §9g, the ladder's last rung): two independent axes — void the
   * backing transaction or keep it, remove the image files or keep them. The receipt row is
   * soft-deleted either way and <em>keeps</em> its {@code transaction_id}: every live-link query
   * scopes to {@code deleted_at is null}, so unlinking is an effect of the delete rather than a
   * column write, and the audit trail survives. A kept transaction is thereafter indistinguishable
   * from a hand-entered one — intended.
   *
   * @throws IllegalStateException if the receipt is not {@code committed}
   * @throws IllegalArgumentException if the receipt does not exist
   */
  public void deleteCommitted(long receiptId, boolean voidTransaction, boolean removeFiles) {
    Receipt receipt = requireCommitted(receiptId);
    if (voidTransaction && receipt.transactionId() != null) {
      dockCommitService.voidTransactionIfLive(receipt.transactionId());
    }
    receiptService.deleteCommitted(receiptId, removeFiles);
  }

  private Receipt requireProcessed(long receiptId) {
    Receipt receipt = requireLive(receiptId);
    if (!ReceiptState.PROCESSED.equals(receipt.state())) {
      throw new IllegalStateException(
          "Only a processed receipt can be confirmed, not " + receipt.state());
    }
    return receipt;
  }

  private Receipt requireCommitted(long receiptId) {
    Receipt receipt = requireLive(receiptId);
    if (!ReceiptState.COMMITTED.equals(receipt.state())) {
      throw new IllegalStateException(
          "Only a committed receipt has a booked transaction, not " + receipt.state());
    }
    return receipt;
  }

  private Receipt requireLive(long receiptId) {
    Optional<Receipt> receipt = receiptService.findById(receiptId);
    return receipt.orElseThrow(() -> vanished(receiptId));
  }

  private static IllegalArgumentException vanished(long receiptId) {
    return new IllegalArgumentException("No live receipt with id " + receiptId);
  }
}
