package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.ImportReviewService.CommitReadiness;
import volkovandr.hauptbuch.importer.StagedCommitData.CommitData;
import volkovandr.hauptbuch.importer.repository.ImportTransactionRepository;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.TransactionDraft;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportCommitService} orchestration with every collaborator
 * mocked (plan f2). Proves it re-runs the duplicate scan, refuses unless the whole commit gate is
 * open, books only the {@code ready} non-opening-balance rows the owner did not {@code skip},
 * delegates the opening-balance reconciliation, and marks the session committed last. The
 * staged-transaction shaping is {@link StagedTransactionResolverTest}'s; the opening-balance
 * outcomes are {@link OpeningBalanceCommitTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class ImportCommitServiceTest {

  private static final long SESSION_ID = 7L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportTransactionRepository importTransactionRepository;
  @Mock ImportDuplicateScanService importDuplicateScanService;
  @Mock ImportReviewService importReviewService;
  @Mock ImportStagingPurge importStagingPurge;
  @Mock StagedCommitData stagedCommitData;
  @Mock StagedTransactionResolver stagedTransactionResolver;
  @Mock OpeningBalanceCommit openingBalanceCommit;
  @Mock LedgerService ledgerService;

  private ImportCommitService service() {
    return new ImportCommitService(
        importSessionService,
        importTransactionRepository,
        importDuplicateScanService,
        importReviewService,
        importStagingPurge,
        stagedCommitData,
        stagedTransactionResolver,
        openingBalanceCommit,
        ledgerService);
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(new ImportSession(SESSION_ID, "open", "utf_8", "day_month", null, null)));
  }

  private void gateOpen() {
    when(importReviewService.commitReadiness()).thenReturn(new CommitReadiness(true, true));
  }

  @Test
  void refusesWhenNoSessionIsOpen() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().commit(n -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No import session is open");
    verifyNoInteractions(ledgerService);
  }

  @Test
  void reRunsTheScanThenRefusesWhenTheGateIsLocked() {
    openSession();
    when(importReviewService.commitReadiness()).thenReturn(new CommitReadiness(false, true));

    assertThatThrownBy(() -> service().commit(n -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready to commit");
    verify(importDuplicateScanService).runScan();
    verifyNoInteractions(ledgerService);
    verify(importSessionService, never()).markCommitted(anyLong());
  }

  @Test
  void namesTheDuplicateScanWhenThatIsWhatIsMissing() {
    openSession();
    when(importReviewService.commitReadiness()).thenReturn(new CommitReadiness(false, false));

    assertThatThrownBy(() -> service().commit(n -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ledger duplicate scan");
  }

  @Test
  void booksEveryReadyNonOpeningBalanceTransactionThenMarksCommitted() {
    openSession();
    gateOpen();
    ImportTransaction ordinary = txn(1L, false);
    ImportTransaction opening = txn(2L, true);
    TransactionDraft draft =
        TransactionDraft.confirmed(LocalDate.of(2016, 6, 6), null, null, List.of());
    when(stagedCommitData.forSession(SESSION_ID))
        .thenReturn(
            data(List.of(ordinary, opening), Map.of(1L, List.of(), 2L, List.of()), Set.of()));
    when(stagedTransactionResolver.resolve(eq(ordinary), any(), any())).thenReturn(draft);
    when(ledgerService.recordTransaction(draft)).thenReturn(99L);
    when(openingBalanceCommit.apply(any())).thenReturn(1);

    ImportCommitService.CommitResult result = service().commit(n -> {});

    assertThat(result.booked()).isEqualTo(1);
    assertThat(result.skipped()).isZero();
    assertThat(result.openingBalances()).isEqualTo(1);
    verify(stagedTransactionResolver, never()).resolve(eq(opening), any(), any());

    InOrder order = inOrder(ledgerService, openingBalanceCommit, importSessionService);
    order.verify(ledgerService).recordTransaction(draft);
    order.verify(openingBalanceCommit).apply(any());
    order.verify(importSessionService).markCommitted(SESSION_ID);
  }

  @Test
  void skippedTransactionIsNotBooked() {
    openSession();
    gateOpen();
    ImportTransaction skipMe = txn(1L, false);
    ImportTransaction keepMe = txn(2L, false);
    TransactionDraft draft =
        TransactionDraft.confirmed(LocalDate.of(2016, 6, 6), null, null, List.of());
    when(stagedCommitData.forSession(SESSION_ID))
        .thenReturn(
            data(List.of(skipMe, keepMe), Map.of(1L, List.of(), 2L, List.of()), Set.of(1L)));
    when(stagedTransactionResolver.resolve(eq(keepMe), any(), any())).thenReturn(draft);
    when(ledgerService.recordTransaction(draft)).thenReturn(99L);

    ImportCommitService.CommitResult result = service().commit(n -> {});

    assertThat(result.booked()).isEqualTo(1);
    assertThat(result.skipped()).isEqualTo(1);
    verify(stagedTransactionResolver, never()).resolve(eq(skipMe), any(), any());
  }

  @Test
  void progressCountsEveryProcessedRowBookedOrSkipped() {
    openSession();
    gateOpen();
    ImportTransaction one = txn(1L, false);
    ImportTransaction skipped = txn(2L, false);
    ImportTransaction three = txn(3L, false);
    TransactionDraft draft =
        TransactionDraft.confirmed(LocalDate.of(2016, 6, 6), null, null, List.of());
    when(stagedCommitData.forSession(SESSION_ID))
        .thenReturn(
            data(
                List.of(one, skipped, three),
                Map.of(1L, List.of(), 2L, List.of(), 3L, List.of()),
                Set.of(2L)));
    lenient().when(stagedTransactionResolver.resolve(any(), any(), any())).thenReturn(draft);

    List<Integer> seen = new ArrayList<>();
    service().commit(seen::add);

    assertThat(seen).containsExactly(1, 2, 3);
  }

  @Test
  void clearStagingDelegatesToTheStagingPurge() {
    service().clearStaging(SESSION_ID);

    verify(importStagingPurge).purge(SESSION_ID);
  }

  private static ImportTransaction txn(long id, boolean openingBalance) {
    return new ImportTransaction(
        id,
        1L,
        LocalDate.of(2016, 6, 6),
        null,
        false,
        null,
        null,
        "unreconciled",
        openingBalance,
        "ready",
        null);
  }

  private CommitData data(
      List<ImportTransaction> committable, Map<Long, List<ImportPosting>> legs, Set<Long> skipped) {
    return new CommitData(
        committable,
        legs,
        new StagedTransactionResolver.Maps("EUR", Map.of(), Map.of(), Map.of(), Map.of()),
        Map.of(),
        List.of(),
        List.of(),
        skipped);
  }
}
