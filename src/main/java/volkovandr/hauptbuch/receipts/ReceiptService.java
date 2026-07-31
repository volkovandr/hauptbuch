package volkovandr.hauptbuch.receipts;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Receipt lifecycle operations: capture a scan, list it (mobile grid / PC register), serve its
 * images, run the delete ladder, and drive the pre-process step (9c) — save the edited image + note
 * + recipe, or discard those edits. Later slices add analyse and confirm.
 *
 * <p>The service upholds the receipt invariants (CLAUDE.md §7): capture stores the image before the
 * row exists, so a persisted receipt always has a file on disk; the delete ladder refuses a {@code
 * committed} receipt (that takes 9g's 5-way dialog); pre-process/discard-edits are gated to the
 * states that can carry them.
 */
@Service
@Transactional
public class ReceiptService {

  /** Source tag for a phone capture (data-model §13.1 {@code source} check). */
  public static final String SOURCE_MOBILE = "mobile";

  /** Source tag for a desktop upload. */
  public static final String SOURCE_PC = "pc";

  /** The mobile grid's bounded window: the last 90 days of captures (§9b, a plain constant). */
  static final int MOBILE_WINDOW_DAYS = 90;

  private final ReceiptRepository receiptRepository;
  private final ReceiptStorage receiptStorage;

  ReceiptService(ReceiptRepository receiptRepository, ReceiptStorage receiptStorage) {
    this.receiptRepository = receiptRepository;
    this.receiptStorage = receiptStorage;
  }

  /**
   * Capture an uploaded image: validate and store it (JPEG/PNG, size cap — {@link ReceiptStorage}),
   * then persist a {@code new} receipt referencing the stored original. The file is written before
   * the row, so a row without its scan can never exist.
   *
   * @throws ReceiptFormatException if the bytes are not an accepted image within the size cap
   */
  public Receipt capture(byte[] imageBytes, String source) {
    String originalPath = receiptStorage.storeOriginal(imageBytes);
    return receiptRepository.insertCaptured(source, originalPath);
  }

  /** A live receipt by id, or empty. */
  public Optional<Receipt> findById(long receiptId) {
    return receiptRepository.findById(receiptId);
  }

  /**
   * The PC register list for the given state filter and (optional) capture-date lower bound (§5).
   */
  public List<Receipt> forRegister(List<String> states, LocalDate from) {
    return receiptRepository.findForRegister(states, from);
  }

  /** The mobile grid: all live receipts captured within the last {@link #MOBILE_WINDOW_DAYS}. */
  public List<Receipt> forMobile() {
    OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(MOBILE_WINDOW_DAYS);
    return receiptRepository.findForMobile(since);
  }

  /** The original scan's bytes, for full-scale display; empty if the receipt is gone. */
  public Optional<byte[]> originalBytes(long receiptId) {
    return findById(receiptId).map(r -> receiptStorage.readImage(r.originalPath()));
  }

  /**
   * The edited image's bytes — the exact copy the AI receives (§6.1), for the {@code pre_processed}
   * view's full-scale display; empty if the receipt is gone or has no edited image yet.
   */
  public Optional<byte[]> editedBytes(long receiptId) {
    return findById(receiptId)
        .filter(r -> r.editedPath() != null)
        .map(r -> receiptStorage.readImage(r.editedPath()));
  }

  /**
   * The thumbnail bytes (self-healing) for the register/grid preview; empty if the receipt is gone.
   */
  public Optional<byte[]> thumbnailBytes(long receiptId) {
    return findById(receiptId)
        .map(r -> receiptStorage.readThumbnail(r.originalPath(), r.editedPath()));
  }

  /**
   * Delete a receipt per the ladder (§9b): the row is always soft-deleted; its files are removed
   * only when {@code removeFiles} is set. Refuses a {@code committed} receipt — deleting one that
   * backs a transaction is the 5-way dialog's job (9g), not this path.
   *
   * @throws IllegalStateException if the receipt is {@code committed}
   * @throws IllegalArgumentException if the receipt does not exist (or is already deleted)
   */
  public void delete(long receiptId, boolean removeFiles) {
    Receipt receipt = requireLive(receiptId);
    if (ReceiptState.COMMITTED.equals(receipt.state())) {
      throw new IllegalStateException(
          "A committed receipt is deleted through the transaction dialog, not the ladder");
    }
    receiptRepository.softDelete(receiptId);
    if (removeFiles) {
      receiptStorage.deleteFiles(receipt.originalPath(), receipt.editedPath());
    }
  }

  /**
   * Save the pre-process edit (receipt doc §6.1): store the client-side-edited image (the bytes the
   * AI receives), record its {@code editRecipe} (so a re-edit can replay it) and the {@code
   * aiNote}, and move the receipt to {@code pre_processed}. Save <em>always</em> bakes, even with
   * zero adjustments — the edited copy is where EXIF-upright is made physical (data-model §13.1).
   * Valid only from {@code new} (first edit) or {@code pre_processed} (re-edit, overwrite in
   * place).
   *
   * @throws IllegalStateException if the receipt is not in a pre-processable state
   * @throws IllegalArgumentException if the receipt does not exist
   * @throws ReceiptFormatException if the edited bytes are not a JPEG within the size cap
   */
  public void preProcess(long receiptId, byte[] editedBytes, String editRecipe, String aiNote) {
    Receipt receipt = requireLive(receiptId);
    if (!ReceiptState.isPreProcessable(receipt.state())) {
      throw new IllegalStateException(
          "Only a new or pre_processed receipt can be edited, not " + receipt.state());
    }
    String editedPath = receiptStorage.storeEdited(receipt.originalPath(), editedBytes);
    receiptRepository.savePreProcess(receiptId, editedPath, editRecipe, blankToNull(aiNote));
  }

  /**
   * Discard the pre-process edits (the stage-undo, receipt doc §6.1): remove the edited image and
   * recipe, regenerate the thumbnail from the original, and move back to {@code new}. The <em>AI
   * note survives</em> — it describes the receipt, not the pixels. Valid only from {@code
   * pre_processed}.
   *
   * @throws IllegalStateException if the receipt is not {@code pre_processed}
   * @throws IllegalArgumentException if the receipt does not exist
   */
  public void discardEdits(long receiptId) {
    Receipt receipt = requireLive(receiptId);
    if (!ReceiptState.PRE_PROCESSED.equals(receipt.state())) {
      throw new IllegalStateException(
          "Only a pre_processed receipt has edits to discard, not " + receipt.state());
    }
    receiptStorage.discardEdited(receipt.originalPath(), receipt.editedPath());
    receiptRepository.discardEdits(receiptId);
  }

  /**
   * The previous/next receipt around {@code receiptId} within the filtered, ordered register list
   * (§6) — what the processing screen's ↑/↓ navigation walks. A receipt no longer in the list (e.g.
   * its state moved outside the filter) has no neighbours.
   */
  public ReceiptNeighbours neighbours(long receiptId, List<String> states, LocalDate from) {
    List<Receipt> list = receiptRepository.findForRegister(states, from);
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).receiptId() == receiptId) {
        Long prev = i > 0 ? list.get(i - 1).receiptId() : null;
        Long next = i < list.size() - 1 ? list.get(i + 1).receiptId() : null;
        return new ReceiptNeighbours(prev, next);
      }
    }
    return ReceiptNeighbours.NONE;
  }

  /**
   * Summarise which context-menu actions apply to a selection (§5.2). Reads the live receipts among
   * {@code ids} and counts the deletable (non-committed) members — committed ones are skipped.
   */
  public SelectionMenu menuFor(List<Long> ids) {
    List<Receipt> selected = receiptRepository.findLiveByIds(ids);
    int deletable = 0;
    for (Receipt r : selected) {
      if (!ReceiptState.COMMITTED.equals(r.state())) {
        deletable++;
      }
    }
    return new SelectionMenu(selected.size(), deletable);
  }

  /**
   * Delete every valid (non-committed) member of a selection through the ladder, skipping committed
   * ones; returns how many were deleted. {@code removeFiles} applies uniformly to the batch.
   */
  public int deleteSelection(List<Long> ids, boolean removeFiles) {
    int deleted = 0;
    for (Receipt r : receiptRepository.findLiveByIds(ids)) {
      if (!ReceiptState.COMMITTED.equals(r.state())) {
        delete(r.receiptId(), removeFiles);
        deleted++;
      }
    }
    return deleted;
  }

  private Receipt requireLive(long receiptId) {
    return receiptRepository
        .findById(receiptId)
        .orElseThrow(() -> new IllegalArgumentException("No live receipt with id " + receiptId));
  }

  /** Normalise an optional freetext field: a null/blank input is stored as null. */
  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text;
  }
}
