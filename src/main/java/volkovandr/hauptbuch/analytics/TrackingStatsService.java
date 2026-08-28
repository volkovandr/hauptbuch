package volkovandr.hauptbuch.analytics;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.LedgerStatsService;
import volkovandr.hauptbuch.receipts.ReceiptStatsService;

/**
 * Assembles the landing-page tracking-stats line (CONTEXT.md "Tracking stats") from ledger and
 * receipt metrics. Lives in {@code analytics} because it spans {@code ledger} and {@code receipts}
 * and both edges are clean (nothing depends on {@code analytics}); {@code ledger} itself cannot
 * read {@code receipts}.
 *
 * <p>Returns empty — the line is not shown — on a book with no live transactions (there is no
 * anchor for the "keeping track for X" span). When there are transactions but no analyzed receipt,
 * the receipt clause is dropped ({@code receiptsPhrase == null}).
 */
@Service
class TrackingStatsService {

  private final LedgerStatsService ledgerStatsService;
  private final ReceiptStatsService receiptStatsService;

  TrackingStatsService(
      LedgerStatsService ledgerStatsService, ReceiptStatsService receiptStatsService) {
    this.ledgerStatsService = ledgerStatsService;
    this.receiptStatsService = receiptStatsService;
  }

  Optional<TrackingStats> current() {
    Optional<LocalDate> earliest = ledgerStatsService.earliestTransactionDate();
    long transactions = ledgerStatsService.liveTransactionCount();
    if (earliest.isEmpty() || transactions == 0) {
      return Optional.empty();
    }

    String durationPhrase = TrackingStatsText.duration(earliest.get(), LocalDate.now());
    String transactionsPhrase = TrackingStatsText.count(transactions, "transaction");

    long analyzed = receiptStatsService.analyzedCount();
    String receiptsPhrase =
        analyzed == 0
            ? null
            : TrackingStatsText.count(analyzed, "receipt")
                + " analyzed ("
                + TrackingStatsText.humaniseBytes(receiptStatsService.imageStorageBytes())
                + ")";

    return Optional.of(new TrackingStats(durationPhrase, transactionsPhrase, receiptsPhrase));
  }
}
