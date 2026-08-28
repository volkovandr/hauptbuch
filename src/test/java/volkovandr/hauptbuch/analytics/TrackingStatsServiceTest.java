package volkovandr.hauptbuch.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerStatsService;
import volkovandr.hauptbuch.receipts.ReceiptStatsService;

/**
 * Unit tier: {@link TrackingStatsService} orchestration with the ledger and receipt metric services
 * mocked — the empty-book guard, the "drop the receipt clause" rule, and the assembled shape. Exact
 * wording is {@link TrackingStatsTextTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class TrackingStatsServiceTest {

  @Mock private LedgerStatsService ledgerStatsService;
  @Mock private ReceiptStatsService receiptStatsService;
  @InjectMocks private TrackingStatsService service;

  @Test
  void yieldsNothingWhenThereAreNoTransactions() {
    when(ledgerStatsService.earliestTransactionDate()).thenReturn(Optional.empty());
    when(ledgerStatsService.liveTransactionCount()).thenReturn(0L);

    assertThat(service.current()).isEmpty();
    verifyNoInteractions(receiptStatsService);
  }

  @Test
  void yieldsNothingWhenTheCountIsZeroEvenIfAnEarliestDateSomehowExists() {
    when(ledgerStatsService.earliestTransactionDate())
        .thenReturn(Optional.of(LocalDate.now().minusYears(1)));
    when(ledgerStatsService.liveTransactionCount()).thenReturn(0L);

    assertThat(service.current()).isEmpty();
  }

  @Test
  void dropsTheReceiptClauseWhenNoReceiptHasBeenAnalyzed() {
    when(ledgerStatsService.earliestTransactionDate())
        .thenReturn(Optional.of(LocalDate.now().minusMonths(7)));
    when(ledgerStatsService.liveTransactionCount()).thenReturn(1234L);
    when(receiptStatsService.analyzedCount()).thenReturn(0L);

    TrackingStats stats = service.current().orElseThrow();

    assertThat(stats.transactionsPhrase()).isEqualTo("1.234 transactions");
    assertThat(stats.receiptsPhrase()).isNull();
  }

  @Test
  void includesTheReceiptClauseWithStorageSizeWhenReceiptsHaveBeenAnalyzed() {
    when(ledgerStatsService.earliestTransactionDate())
        .thenReturn(Optional.of(LocalDate.now().minusMonths(3)));
    when(ledgerStatsService.liveTransactionCount()).thenReturn(42L);
    when(receiptStatsService.analyzedCount()).thenReturn(800L);
    when(receiptStatsService.imageStorageBytes()).thenReturn(2_500_000_000L);

    TrackingStats stats = service.current().orElseThrow();

    assertThat(stats.transactionsPhrase()).isEqualTo("42 transactions");
    assertThat(stats.receiptsPhrase()).isEqualTo("800 receipts analyzed (2,5 GB)");
    assertThat(stats.durationPhrase()).isNotBlank();
  }

  @Test
  void singularNounsForOne() {
    when(ledgerStatsService.earliestTransactionDate())
        .thenReturn(Optional.of(LocalDate.now().minusMonths(2)));
    when(ledgerStatsService.liveTransactionCount()).thenReturn(1L);
    when(receiptStatsService.analyzedCount()).thenReturn(1L);
    when(receiptStatsService.imageStorageBytes()).thenReturn(1_000L);

    TrackingStats stats = service.current().orElseThrow();

    assertThat(stats.transactionsPhrase()).isEqualTo("1 transaction");
    assertThat(stats.receiptsPhrase()).startsWith("1 receipt analyzed (");
  }
}
