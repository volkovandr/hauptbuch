package volkovandr.hauptbuch.ledger;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Read-only ledger metrics for surfaces that summarise the book rather than operate on it — the
 * landing page's tracking-stats line (CONTEXT.md "Tracking stats"). Kept apart from {@link
 * LedgerService}, which is the write engine and its transactional reads, so those two concerns stay
 * separate and this stays a trivially-testable pair of queries.
 */
@Service
public class LedgerStatsService {

  private final TransactionRepository transactionRepository;

  LedgerStatsService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  /**
   * How many transactions are currently in the ledger — live, any lifecycle, opening balances
   * included.
   */
  public long liveTransactionCount() {
    return transactionRepository.countLive();
  }

  /** The booking date of the earliest live transaction, or empty on a book with none. */
  public Optional<LocalDate> earliestTransactionDate() {
    return transactionRepository.earliestLiveDate();
  }
}
