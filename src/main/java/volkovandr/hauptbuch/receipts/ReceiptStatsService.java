package volkovandr.hauptbuch.receipts;

import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Read-only receipt metrics for surfaces that summarise the collection rather than work a single
 * receipt — the landing page's tracking-stats line (CONTEXT.md "Tracking stats"). Kept apart from
 * {@link ReceiptService}, which owns the receipt lifecycle, so this stays a thin, focused pair of
 * reads.
 */
@Service
public class ReceiptStatsService {

  private final ReceiptRepository receiptRepository;
  private final ReceiptStorageFootprint receiptStorageFootprint;

  ReceiptStatsService(
      ReceiptRepository receiptRepository, ReceiptStorageFootprint receiptStorageFootprint) {
    this.receiptRepository = receiptRepository;
    this.receiptStorageFootprint = receiptStorageFootprint;
  }

  /** How many live receipts the parser has analyzed (a parse response is stored). */
  public long analyzedCount() {
    return receiptRepository.countAnalyzed();
  }

  /** Total bytes of stored receipt imagery — originals, edited derivatives, thumbnails. */
  public long imageStorageBytes() {
    return receiptStorageFootprint.totalBytes();
  }
}
