package volkovandr.hauptbuch.receipts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.ledger.LedgerService;
import volkovandr.hauptbuch.ledger.PayeeService;
import volkovandr.hauptbuch.ledger.Transaction;
import volkovandr.hauptbuch.receipts.repository.ReceiptRepository;

/**
 * Unit tier (§1.5): {@link ReceiptService} orchestration and invariant guards with the repository
 * and storage mocked — capture ordering (file before row), the delete ladder's file choice, the
 * committed refusal, and discard validity.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

  private static final String ORIGINAL = "originals/2026/07/20260730-143022123.jpg";
  private static final String EDITED = "edited/2026/07/20260730-143022123.jpg";

  @Mock ReceiptRepository receiptRepository;
  @Mock volkovandr.hauptbuch.receipts.repository.ReceiptLineRepository receiptLineRepository;
  @Mock ReceiptStorage receiptStorage;
  @Mock PayeeService payeeService;
  @Mock LedgerService ledgerService;
  @InjectMocks ReceiptService service;

  private Receipt receiptInState(String state) {
    return receipt(7L, state, null);
  }

  private Receipt receipt(long id, String state, String editedPath) {
    return receiptWithMerchant(id, state, editedPath, null, null, null, null);
  }

  private Receipt receiptWithMerchant(
      long id,
      String state,
      String editedPath,
      String merchantText,
      String merchantCity,
      String merchantCountry,
      Long payeeId) {
    return receiptWithTransaction(
        id, state, editedPath, merchantText, merchantCity, merchantCountry, payeeId, null);
  }

  private Receipt committedReceipt(long id, Long transactionId) {
    return receiptWithTransaction(id, "committed", null, null, null, null, null, transactionId);
  }

  private Receipt receiptWithTransaction(
      long id,
      String state,
      String editedPath,
      String merchantText,
      String merchantCity,
      String merchantCountry,
      Long payeeId,
      Long transactionId) {
    return new Receipt(
        id,
        state,
        OffsetDateTime.now(),
        "mobile",
        ORIGINAL,
        editedPath,
        null,
        null,
        null,
        null,
        merchantText,
        null,
        null,
        null,
        null,
        transactionId,
        null,
        // 9e telemetry (parseError … parseCost), then merchantCity/merchantCountry
        null,
        null,
        null,
        null,
        null,
        null,
        merchantCity,
        merchantCountry,
        null,
        null,
        payeeId,
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
  void preProcessStoresTheEditedImageThenSavesTheRow() {
    byte[] edited = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("new")));
    when(receiptStorage.storeEdited(ORIGINAL, edited)).thenReturn(EDITED);

    service.preProcess(7L, edited, "{\"rotate\":90}", "this is fuel");

    verify(receiptStorage).storeEdited(ORIGINAL, edited);
    verify(receiptRepository).savePreProcess(7L, EDITED, "{\"rotate\":90}", "this is fuel");
  }

  @Test
  void preProcessFromPreProcessedReEditsInPlace() {
    byte[] edited = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
    when(receiptRepository.findById(7L))
        .thenReturn(Optional.of(receipt(7L, "pre_processed", EDITED)));
    when(receiptStorage.storeEdited(ORIGINAL, edited)).thenReturn(EDITED);

    service.preProcess(7L, edited, "{}", "  ");

    // A blank AI note is normalised to null.
    verify(receiptRepository).savePreProcess(7L, EDITED, "{}", null);
  }

  @Test
  void preProcessRefusesProcessedReceipt() {
    byte[] edited = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1};
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("processed")));

    assertThatThrownBy(() -> service.preProcess(7L, edited, "{}", null))
        .isInstanceOf(IllegalStateException.class);

    verifyNoInteractions(receiptStorage);
  }

  @Test
  void discardEditsRemovesTheEditedImageThenClearsTheRow() {
    when(receiptRepository.findById(7L))
        .thenReturn(Optional.of(receipt(7L, "pre_processed", EDITED)));

    service.discardEdits(7L);

    verify(receiptStorage).discardEdited(ORIGINAL, EDITED);
    verify(receiptRepository).discardEdits(7L);
  }

  @Test
  void discardEditsRefusesReceiptWithoutEdits() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.of(receiptInState("new")));

    assertThatThrownBy(() -> service.discardEdits(7L)).isInstanceOf(IllegalStateException.class);

    verify(receiptRepository, never()).discardEdits(7L);
  }

  @Test
  void neighboursReturnsThePreviousAndNextInTheFilteredList() {
    when(receiptRepository.findForRegister(any(), any(), eq(false), eq(true)))
        .thenReturn(
            List.of(receipt(1L, "new", null), receipt(2L, "new", null), receipt(3L, "new", null)));

    ReceiptNeighbours neighbours = service.neighbours(2L, List.of("new"), null);

    assertThat(neighbours.prev()).isEqualTo(1L);
    assertThat(neighbours.next()).isEqualTo(3L);
  }

  @Test
  void neighboursAreNullAtTheEndsAndWhenAbsent() {
    when(receiptRepository.findForRegister(any(), any(), eq(false), eq(true)))
        .thenReturn(List.of(receipt(1L, "new", null), receipt(2L, "new", null)));

    assertThat(service.neighbours(1L, List.of("new"), null).prev()).isNull();
    assertThat(service.neighbours(2L, List.of("new"), null).next()).isNull();
    // A receipt not in the filtered set has no neighbours.
    assertThat(service.neighbours(99L, List.of("new"), null)).isEqualTo(ReceiptNeighbours.NONE);
  }

  /**
   * The context menu's two counts are independent axes: everything non-committed is deletable, only
   * {@code pre_processed} members can go into a batch (9h).
   */
  @Test
  void menuCountsDeletableAndProcessableMembersSeparately() {
    when(receiptRepository.findLiveByIds(any()))
        .thenReturn(
            List.of(
                receipt(1L, "pre_processed", EDITED),
                receipt(2L, "new", null),
                receipt(3L, "committed", EDITED)));

    SelectionMenu menu = service.menuFor(List.of(1L, 2L, 3L));

    assertThat(menu.total()).isEqualTo(3);
    assertThat(menu.deletable()).isEqualTo(2);
    assertThat(menu.processable()).isEqualTo(1);
    assertThat(menu.skipped()).isEqualTo(1);
    assertThat(menu.processSkipped()).isEqualTo(2);
    assertThat(menu.processableLabel()).isEqualTo("1 receipt");
    assertThat(menu.deletableLabel()).isEqualTo("2 receipts");
  }

  @Test
  void menuOffersNothingForAnEmptySelection() {
    when(receiptRepository.findLiveByIds(any())).thenReturn(List.of());

    SelectionMenu menu = service.menuFor(List.of());

    assertThat(menu.canDelete()).isFalse();
    assertThat(menu.canProcess()).isFalse();
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
  void stillProcessingReturnsOnlyTheWatchedIdsStillInThatState() {
    when(receiptRepository.findLiveByIds(List.of(1L, 2L, 3L)))
        .thenReturn(
            List.of(
                receipt(1L, "processing", EDITED),
                receipt(2L, "processed", EDITED),
                receipt(3L, "processing", EDITED)));

    assertThat(service.stillProcessing(List.of(1L, 2L, 3L))).containsExactly(1L, 3L);
  }

  /**
   * {@link ReceiptRepository#findLiveByIds} already excludes soft-deleted rows, so a receipt
   * deleted mid-batch simply drops out of the result — the caller reads that as "changed" because
   * the returned list is shorter than the ids it watched.
   */
  @Test
  void stillProcessingDropsIdsThatWereSoftDeletedMidFlight() {
    when(receiptRepository.findLiveByIds(List.of(1L, 2L)))
        .thenReturn(List.of(receipt(1L, "processing", EDITED)));

    assertThat(service.stillProcessing(List.of(1L, 2L))).containsExactly(1L);
  }

  @Test
  void originalBytesReturnsEmptyForMissingReceipt() {
    when(receiptRepository.findById(7L)).thenReturn(Optional.empty());

    assertThat(service.originalBytes(7L)).isEmpty();
    verifyNoInteractions(receiptStorage);
  }

  /**
   * The register's Merchant column precedence (issue tracker #07): assigned payee's name, else the
   * AI's merchant composite, else blank (omitted from the map). One batched payee lookup for the
   * whole page, not a per-row call.
   */
  @Test
  void merchantDisplaysAppliesThePayeeThenParseFallbackPrecedence() {
    Receipt withPayee = receiptWithMerchant(1L, "processed", null, "Rewe Raw", null, null, 42L);
    Receipt parsedOnly =
        receiptWithMerchant(2L, "processed", null, null, "Berlin", "Germany", null);
    Receipt blank = receiptWithMerchant(3L, "new", null, null, null, null, null);
    // A real HashMap, not Map.of(...): production's Collectors.toMap-built map permits the
    // null-payee lookup below (Map.of()'s immutable map would throw on a null key).
    when(payeeService.namesFor(List.of(42L))).thenReturn(new HashMap<>(Map.of(42L, "Rewe")));

    Map<Long, String> displays = service.merchantDisplays(List.of(withPayee, parsedOnly, blank));

    assertThat(displays)
        .containsEntry(1L, "Rewe")
        .containsEntry(2L, "Berlin - Germany")
        .doesNotContainKey(3L);
  }

  @Test
  void merchantDisplaysFallsBackToParseCompositeWhenThePayeeNameIsMissing() {
    Receipt withPayee = receiptWithMerchant(1L, "processed", null, "Rewe Raw", null, null, 42L);
    when(payeeService.namesFor(List.of(42L))).thenReturn(Map.of());

    Map<Long, String> displays = service.merchantDisplays(List.of(withPayee));

    assertThat(displays).containsEntry(1L, "Rewe Raw");
  }

  // ── The register's transaction-date column (issue tracker #09) ───────────────

  /**
   * Only receipts with a linked transaction get a date, resolved by one batched ledger lookup for
   * the whole list, mirroring the Merchant-column precedent (issue tracker #07).
   */
  @Test
  void transactionDatesResolvesOnlyReceiptsWithLinkedTransaction() {
    Receipt withTransaction = committedReceipt(1L, 70L);
    Receipt notCommitted = receiptInState("processed");
    // A real HashMap, not Map.of(...): production's Collectors.toMap-built map permits the
    // null-transaction-id lookup below (Map.of()'s immutable map would throw on a null key).
    when(ledgerService.datesForTransactions(List.of(70L)))
        .thenReturn(new HashMap<>(Map.of(70L, LocalDate.of(2026, 6, 1))));

    Map<Long, LocalDate> dates = service.transactionDates(List.of(withTransaction, notCommitted));

    assertThat(dates).containsEntry(1L, LocalDate.of(2026, 6, 1)).doesNotContainKey(7L);
  }

  @Test
  void transactionDatesSkipsTheLedgerLookupWhenNothingIsLinked() {
    assertThat(service.transactionDates(List.of(receiptInState("new")))).isEmpty();
    verifyNoInteractions(ledgerService);
  }

  // ── The voided-transaction display fact (issue tracker #08) ──────────────────

  @Test
  void transactionVoidedIsFalseWithNoLinkedTransaction() {
    assertThat(service.transactionVoided(committedReceipt(1L, null))).isFalse();
    verifyNoInteractions(ledgerService);
  }

  @Test
  void transactionVoidedIsTrueWhenTheLinkedTransactionIsGone() {
    when(ledgerService.findTransaction(70L)).thenReturn(Optional.empty());

    assertThat(service.transactionVoided(committedReceipt(1L, 70L))).isTrue();
  }

  @Test
  void transactionVoidedIsFalseWhenTheLinkedTransactionIsStillLive() {
    when(ledgerService.findTransaction(70L))
        .thenReturn(
            Optional.of(
                new Transaction(70L, LocalDate.now(), null, null, "confirmed", null, null, null)));

    assertThat(service.transactionVoided(committedReceipt(1L, 70L))).isFalse();
  }

  /**
   * The batched sibling for a list/grid render (issue tracker #08): only committed receipts with a
   * transaction the ledger reports voided make it into the result, one lookup for the whole page.
   */
  @Test
  void voidedReceiptIdsReturnsOnlyCommittedReceiptsWhoseTransactionIsVoided() {
    Receipt live = committedReceipt(1L, 70L);
    Receipt voided = committedReceipt(2L, 71L);
    Receipt notCommitted = receiptInState("processed");
    when(ledgerService.voidedTransactionIds(List.of(70L, 71L))).thenReturn(Set.of(71L));

    Set<Long> voidedReceiptIds = service.voidedReceiptIds(List.of(live, voided, notCommitted));

    assertThat(voidedReceiptIds).containsExactly(2L);
  }

  @Test
  void voidedReceiptIdsSkipsTheLedgerLookupWhenNothingIsCommitted() {
    assertThat(service.voidedReceiptIds(List.of(receiptInState("new")))).isEmpty();
    verifyNoInteractions(ledgerService);
  }
}
