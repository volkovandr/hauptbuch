package volkovandr.hauptbuch.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import volkovandr.hauptbuch.ledger.repository.PendingReviewCount;
import volkovandr.hauptbuch.ledger.repository.TransactionRepository;

/**
 * Unit tier (CLAUDE.md §6): the main page's "pending to review" line is hidden at zero and
 * otherwise carries the counts, with "today" (the overdue cut-off) taken from the {@link Clock}.
 */
class PendingReviewServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

  private final TransactionRepository transactionRepository = mock();
  private final PendingReviewService service =
      new PendingReviewService(
          transactionRepository,
          Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

  @Test
  void hiddenWhenNothingIsPending() {
    when(transactionRepository.countPendingReview(TODAY))
        .thenReturn(new PendingReviewCount(0, 0, null));

    assertThat(service.current()).isEmpty();
  }

  @Test
  void carriesTheCountsAndTheEarliestDateCountedAsOfToday() {
    LocalDate earliest = LocalDate.of(2026, 9, 1);
    when(transactionRepository.countPendingReview(TODAY))
        .thenReturn(new PendingReviewCount(3, 1, earliest));

    assertThat(service.current()).contains(new PendingReviewCount(3, 1, earliest));
  }
}
