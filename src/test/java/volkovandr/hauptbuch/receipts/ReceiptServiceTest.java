package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Unit tier (§1.5): {@link ReceiptService} orchestration and invariant guards with the repository
 * and storage mocked — capture ordering (file before row), the delete ladder's file choice, the
 * committed refusal, and discard validity.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

  private static final String ORIGINAL = "originals/2026/07/20260730-143022123.jpg";

  @Mock ReceiptRepository receiptRepository;
  @Mock ReceiptStorage receiptStorage;
  @InjectMocks ReceiptService service;

  private Receipt receiptInState(String state) {
    return new Receipt(
        7L,
        state,
        OffsetDateTime.now(),
        "mobile",
        ORIGINAL,
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

  @Test
  void captureStoresTheImageThenInsertsTheRow() {
    byte[] bytes = {1, 2, 3};
    when(receiptStorage.storeOriginal(bytes)).thenReturn(ORIGINAL);
    when(receiptRepository.insertCaptured("mobile", ORIGINAL)).thenReturn(receiptInState("new"));

    service.capture(bytes, ReceiptService.SOURCE_MOBILE);

    verify(receiptStorage).storeOriginal(bytes);
    verify(receiptRepository).insertCaptured("mobile", ORIGINAL);
  }

  @Test
  void deleteNewReceiptSoftDeletesAndRemovesFiles() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("new")));

    service.delete(7L, true);

    verify(receiptRepository).softDelete(7L);
    verify(receiptStorage).deleteFiles(ORIGINAL, null);
  }

  @Test
  void deleteWithKeepFilesSoftDeletesButLeavesFilesOnDisk() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("pre_processed")));

    service.delete(7L, false);

    verify(receiptRepository).softDelete(7L);
    verify(receiptStorage, never()).deleteFiles(ORIGINAL, null);
  }

  @Test
  void deleteRefusesCommittedReceipt() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("committed")));

    assertThatThrownBy(() -> service.delete(7L, true)).isInstanceOf(IllegalStateException.class);

    verify(receiptRepository, never()).softDelete(7L);
    verify(receiptStorage, never()).deleteFiles(ORIGINAL, null);
  }

  @Test
  void deleteRejectsAnUnknownReceipt() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.delete(7L, true)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void discardMovesNonCommittedReceiptToDiscarded() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("new")));

    service.discard(7L);

    verify(receiptRepository).updateState(7L, "discarded");
  }

  @Test
  void discardRefusesCommittedReceipt() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("committed")));

    assertThatThrownBy(() -> service.discard(7L)).isInstanceOf(IllegalStateException.class);

    verify(receiptRepository, never()).updateState(eq(7L), eq("discarded"));
  }

  @Test
  void mobileListQueriesThe90DayWindow() {
    when(receiptRepository.findForMobile(any())).thenReturn(List.of());

    service.forMobile();

    ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(receiptRepository).findForMobile(since.capture());
    // ~90 days back from now (allow generous slack for test execution time).
    OffsetDateTime expected = OffsetDateTime.now().minusDays(90);
    assertThat(since.getValue()).isBetween(expected.minusMinutes(5), expected.plusMinutes(5));
  }

  @Test
  void originalBytesReturnsEmptyForMissingReceipt() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.empty());

    assertThat(service.originalBytes(7L)).isEmpty();
    verifyNoInteractions(receiptStorage);
  }
}
