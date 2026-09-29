package volkovandr.hauptbuch.ledger;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import volkovandr.hauptbuch.ledger.repository.PendingReviewCount;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Builds the main page's "N pending to review" line (register §2.3, recurring plan slice d): the
 * live {@code pending_review} transactions, with the overdue ones (dated before today) called out.
 * "Today" comes from the injected {@link Clock}.
 */
@Service
class PendingReviewService {

  private final TransactionRepository transactionRepository;
  private final Clock clock;

  PendingReviewService(TransactionRepository transactionRepository, Clock clock) {
    this.transactionRepository = transactionRepository;
    this.clock = clock;
  }

  /**
   * The line's counts, or empty when nothing is pending — the line is hidden at zero, so the
   * template never decides whether to draw it. The link opens the register from the earliest
   * pending date, so it shows every pending row however old, not just the default last 12 months.
   */
  Optional<PendingReviewCount> current() {
    PendingReviewCount count = transactionRepository.countPendingReview(LocalDate.now(clock));
    return count.pending() == 0 ? Optional.empty() : Optional.of(count);
  }
}
