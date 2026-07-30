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
 * Receipt lifecycle operations for the 9b walking skeleton: capture a scan, list it (mobile grid /
 * PC register), serve its images, and run the delete ladder + discard. Later slices add
 * pre-process, analyse, and confirm.
 *
 * <p>The service upholds the receipt invariants (CLAUDE.md §7): capture stores the image before the
 * row exists, so a persisted receipt always has a file on disk; the delete ladder refuses a {@code
 * committed} receipt (that takes 9g's 5-way dialog); discard is only valid on a non-committed
 * state.
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
   * Discard a receipt — "looked at, chose not to book" (§2.1). Sets state to {@code discarded}; the
   * row stays live (orthogonal to soft-delete) and its files are kept. Only valid on a
   * non-committed receipt.
   *
   * @throws IllegalStateException if the receipt is already committed
   * @throws IllegalArgumentException if the receipt does not exist
   */
  public void discard(long receiptId) {
    Receipt receipt = requireLive(receiptId);
    if (!ReceiptState.canDiscard(receipt.state())) {
      throw new IllegalStateException(
          "Only a non-committed receipt can be discarded, not " + receipt.state());
    }
    receiptRepository.updateState(receiptId, ReceiptState.DISCARDED);
  }

  /**
   * Summarise which context-menu actions apply to a selection (§5.2). Reads the live receipts among
   * {@code ids} and counts deletable/discardable members and whether they are all {@code new}.
   */
  public SelectionMenu menuFor(List<Long> ids) {
    List<Receipt> selected = receiptRepository.findLiveByIds(ids);
    int deletable = 0;
    boolean allNew = true;
    for (Receipt r : selected) {
      if (!ReceiptState.COMMITTED.equals(r.state())) {
        deletable++;
        allNew &= ReceiptState.deletesInstantly(r.state());
      }
    }
    // Every non-committed member is both deletable and discardable (§2.1); `allNew` only decides
    // whether delete is instant or routes through the file-choice dialog.
    return new SelectionMenu(selected.size(), deletable, deletable, allNew);
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

  /** Discard every valid (non-committed) member of a selection; returns how many were discarded. */
  public int discardSelection(List<Long> ids) {
    int discarded = 0;
    for (Receipt r : receiptRepository.findLiveByIds(ids)) {
      if (ReceiptState.canDiscard(r.state())) {
        discard(r.receiptId());
        discarded++;
      }
    }
    return discarded;
  }

  private Receipt requireLive(long receiptId) {
    return receiptRepository
        .findById(receiptId)
        .orElseThrow(() -> new IllegalArgumentException("No live receipt with id " + receiptId));
  }
}
