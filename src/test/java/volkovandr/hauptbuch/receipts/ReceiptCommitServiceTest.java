package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.operations.DockCommitService;
import volkovandr.hauptbuch.operations.DockSplitService;
import volkovandr.hauptbuch.operations.SplitCurrency;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Unit tier (§1.5): {@link ReceiptCommitService}'s orchestration with the gate, the editor, and the
 * two {@code operations} calls mocked (plan §9g) — that a refused gate writes nothing, that a
 * confirm saves before it books, and that a re-entry voids its predecessor before booking the
 * replacement.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptCommitServiceTest {

  private static final long RECEIPT_ID = 5L;
  private static final long OLD_TXN = 70L;
  private static final long NEW_TXN = 71L;

  @Mock ReceiptService receiptService;
  @Mock ReceiptEditorService receiptEditorService;
  @Mock ReceiptConfirmGate receiptConfirmGate;
  @Mock ReceiptRepository receiptRepository;
  @Mock DockSplitService dockSplitService;
  @Mock DockCommitService dockCommitService;
  @InjectMocks ReceiptCommitService service;

  @Test
  void confirmSavesTheDraftThenBooksItAndLinksTheTransaction() {
    Receipt processed = receipt("processed", null);
    when(receiptService.findById(RECEIPT_ID)).thenReturn(Optional.of(processed));
    when(receiptEditorService.panel(any())).thenReturn(editor());
    when(receiptConfirmGate.problems(any(), any())).thenReturn(List.of());
    when(dockSplitService.commit(any())).thenReturn(NEW_TXN);

    assertThat(service.confirm(RECEIPT_ID, form())).isEqualTo(NEW_TXN);

    // Save first: receipt_line must be exactly what got booked (the audit chain, data-model §13.2).
    InOrder order = inOrder(receiptEditorService, dockSplitService, receiptRepository);
    order.verify(receiptEditorService).save(RECEIPT_ID, form());
    order.verify(dockSplitService).commit(any());
    order.verify(receiptRepository).markCommitted(RECEIPT_ID, NEW_TXN);
    verify(dockCommitService, never()).voidTransactionIfLive(anyLong());
  }

  @Test
  void reEntryVoidsThePredecessorBeforeBookingTheReplacement() {
    // A reopened receipt keeps its transaction link — that is what makes this a re-entry.
    when(receiptService.findById(RECEIPT_ID))
        .thenReturn(Optional.of(receipt("processed", OLD_TXN)));
    when(receiptEditorService.panel(any())).thenReturn(editor());
    when(receiptConfirmGate.problems(any(), any())).thenReturn(List.of());
    when(dockSplitService.commit(any())).thenReturn(NEW_TXN);

    service.confirm(RECEIPT_ID, form());

    InOrder order = inOrder(dockCommitService, dockSplitService, receiptRepository);
    order.verify(dockCommitService).voidTransactionIfLive(OLD_TXN);
    order.verify(dockSplitService).commit(any());
    order.verify(receiptRepository).markCommitted(RECEIPT_ID, NEW_TXN);
  }

  @Test
  void refusedGateWritesNothing() {
    when(receiptService.findById(RECEIPT_ID)).thenReturn(Optional.of(receipt("processed", null)));
    when(receiptConfirmGate.problems(any(), any()))
        .thenReturn(List.of("Enter the receipt's total before confirming."));

    assertThatThrownBy(() -> service.confirm(RECEIPT_ID, form()))
        .isInstanceOf(ReceiptConfirmException.class)
        .hasMessageContaining("total");

    verify(receiptEditorService, never()).save(anyLong(), any());
    verify(dockSplitService, never()).commit(any());
  }

  @Test
  void refusesToConfirmReceiptThatIsNotProcessed() {
    when(receiptService.findById(RECEIPT_ID))
        .thenReturn(Optional.of(receipt("committed", OLD_TXN)));

    assertThatThrownBy(() -> service.confirm(RECEIPT_ID, form()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reopenLeavesTheTransactionAlone() {
    when(receiptService.findById(RECEIPT_ID))
        .thenReturn(Optional.of(receipt("committed", OLD_TXN)));

    service.reopen(RECEIPT_ID);

    verify(receiptRepository).reopen(RECEIPT_ID);
    verify(dockCommitService, never()).voidTransactionIfLive(anyLong());
  }

  @Test
  void committedDeleteVoidsOnlyWhenAsked() {
    when(receiptService.findById(RECEIPT_ID))
        .thenReturn(Optional.of(receipt("committed", OLD_TXN)));

    service.deleteCommitted(RECEIPT_ID, false, true);

    verify(dockCommitService, never()).voidTransactionIfLive(anyLong());
    verify(receiptService).deleteCommitted(RECEIPT_ID, true);
  }

  @Test
  void committedDeleteVoidsTheTransactionWhenAsked() {
    when(receiptService.findById(RECEIPT_ID))
        .thenReturn(Optional.of(receipt("committed", OLD_TXN)));

    service.deleteCommitted(RECEIPT_ID, true, false);

    verify(dockCommitService).voidTransactionIfLive(OLD_TXN);
    verify(receiptService).deleteCommitted(RECEIPT_ID, false);
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /**
   * The assembled readout Confirm passes to the gate and then reads the header currency off — a
   * same-currency receipt here, so the entry books through the single-currency path.
   */
  private static ReceiptEditor editor() {
    return new ReceiptEditor(
        null,
        "",
        1L,
        new SplitCurrency(false, "EUR", "EUR", "EUR", false, "", "", "", "", "0", "0"),
        "10,00",
        "",
        "",
        "0,00",
        true,
        "ok",
        List.of());
  }

  private static ReceiptEditorForm form() {
    return WorkingLine.toForm(
        new ReceiptEditorHeader("2026-08-03", "", 1L, "EUR", "10,00", "", "", "", ""), List.of());
  }

  private static Receipt receipt(String state, Long transactionId) {
    return new Receipt(
        RECEIPT_ID,
        state,
        null,
        "pc",
        "orig.jpg",
        "edit.jpg",
        "{}",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L,
        transactionId,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
