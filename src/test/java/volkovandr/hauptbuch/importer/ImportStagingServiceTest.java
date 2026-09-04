package volkovandr.hauptbuch.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.ImportPreviewService.Parsed;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.importer.repository.ImportPostingRepository;
import volkovandr.hauptbuch.importer.repository.ImportTransactionRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportStagingService} orchestration with every repository mocked
 * — the sign convention of the staged legs (import.md §7), category-vs-transfer routing, map-row
 * accumulation, and the guards (no open session, still-ambiguous date order).
 */
@ExtendWith(MockitoExtension.class)
class ImportStagingServiceTest {

  private static final long SESSION_ID = 1L;
  private static final long FILE_ID = 7L;
  private static final long TX_ID = 99L;

  @Mock ImportSessionService importSessionService;
  @Mock ImportPreviewService importPreviewService;
  @Mock ImportFileRepository importFileRepository;
  @Mock ImportTransactionRepository importTransactionRepository;
  @Mock ImportPostingRepository importPostingRepository;
  @Mock ImportAccountRepository importAccountRepository;
  @Mock ImportCategoryRepository importCategoryRepository;
  @Mock ImportMirrorMatchingService importMirrorMatchingService;

  private ImportStagingService service() {
    return new ImportStagingService(
        importSessionService,
        importPreviewService,
        importFileRepository,
        importTransactionRepository,
        importPostingRepository,
        importAccountRepository,
        importCategoryRepository,
        importMirrorMatchingService);
  }

  private static PendingImportUpload upload() {
    return PendingImportUpload.of("tok", "export.qif", new byte[] {1})
        .withDeducedAccountName("Current Account");
  }

  private void openSession() {
    when(importSessionService.currentSession())
        .thenReturn(
            Optional.of(
                new ImportSession(
                    SESSION_ID, ImportSessionState.OPEN, null, null, OffsetDateTime.now(), null)));
  }

  private void parsed(ImportedFile file, QifDateFormat.Order order) {
    when(importPreviewService.parse(any()))
        .thenReturn(new Parsed(file, null, "utf_8", "utf_8", null, order, "day_month"));
  }

  private void stubFileAndTransactionInserts() {
    when(importFileRepository.insert(any(ImportFile.class)))
        .thenReturn(
            new ImportFile(
                FILE_ID,
                SESSION_ID,
                "export.qif",
                "Current Account",
                "utf_8",
                "day_month",
                "asset",
                1,
                OffsetDateTime.now()));
    when(importTransactionRepository.insert(any(ImportTransaction.class))).thenReturn(TX_ID);
  }

  private static ImportedTransaction simpleExpense(String path, String amount) {
    return new ImportedTransaction(
        "01/07'2004",
        "Grocer",
        false,
        null,
        null,
        ClearedStatus.UNRECONCILED,
        false,
        List.of(
            new ImportedLine(
                new BigDecimal(amount), null, null, new ImportedTarget.CategoryPath(path))));
  }

  private static ImportPosting categoryLeg(String amount, String note, String path) {
    return new ImportPosting(
        null, TX_ID, new BigDecimal(amount), note, path, null, null, null, null, false);
  }

  private static ImportPosting fundingLeg(String amount) {
    return new ImportPosting(
        null, TX_ID, new BigDecimal(amount), null, null, "Current Account", null, null, null, true);
  }

  @Test
  void refusesToStageWithoutAnOpenSession() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().stage(upload()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No import session is open");

    verifyNoInteractions(
        importFileRepository, importTransactionRepository, importPostingRepository);
  }

  @Test
  void refusesToStageUntilTheMoneyAccountIsStated() {
    openSession();

    assertThatThrownBy(
            () -> service().stage(PendingImportUpload.of("tok", "export.qif", new byte[] {1})))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("State which Money account");

    verifyNoInteractions(importPreviewService, importFileRepository);
  }

  @Test
  void refusesToStageWhileTheDateOrderIsStillAmbiguous() {
    openSession();
    parsed(
        new ImportedFile(
            "asset", Set.of("Current Account"), List.of(simpleExpense("Food", "-5.00"))),
        QifDateFormat.Order.AMBIGUOUS);

    assertThatThrownBy(() -> service().stage(upload()))
        .isInstanceOf(QifRejectedException.class)
        .hasMessageContaining("DD/MM");

    verify(importFileRepository, never()).insert(any(ImportFile.class));
  }

  @Test
  void stagesTheFileWithMapRowsAndZeroSumLegsInHauptbuchSign() {
    openSession();
    stubFileAndTransactionInserts();
    parsed(
        new ImportedFile(
            "asset", Set.of("Current Account"), List.of(simpleExpense("Food", "-12.34"))),
        QifDateFormat.Order.DAY_MONTH);

    ImportFile staged = service().stage(upload());

    assertThat(staged.importFileId()).isEqualTo(FILE_ID);
    verify(importFileRepository)
        .insert(
            new ImportFile(
                null,
                SESSION_ID,
                "export.qif",
                "Current Account",
                "utf_8",
                "day_month",
                "asset",
                1,
                null));
    verify(importAccountRepository).upsertUnmapped(SESSION_ID, "Current Account");
    verify(importCategoryRepository).upsertUnmapped(SESSION_ID, "Food");
    verify(importTransactionRepository)
        .insert(
            new ImportTransaction(
                null,
                FILE_ID,
                LocalDate.of(2004, 7, 1),
                "Grocer",
                false,
                null,
                null,
                "unreconciled",
                false,
                null,
                null));
    // Category leg: the negation of Money's amount (an expense debits the category, +).
    verify(importPostingRepository).insert(categoryLeg("12.34", null, "Food"));
    // Funding leg: the transaction total (Σ line amounts) on the file's own account (a credit, −).
    verify(importPostingRepository).insert(fundingLeg("-12.34"));
    // A staged file can supply a transfer's second sighting — re-match runs (plan e1).
    verify(importMirrorMatchingService).rematchCurrentSession();
  }

  @Test
  void routesTransferLegThroughAccountMapNotCategoryMap() {
    openSession();
    stubFileAndTransactionInserts();
    ImportedTransaction transfer =
        new ImportedTransaction(
            "01/07'2004",
            null,
            false,
            null,
            null,
            ClearedStatus.UNRECONCILED,
            false,
            List.of(
                new ImportedLine(
                    new BigDecimal("-200.00"),
                    null,
                    null,
                    new ImportedTarget.AccountReference("Savings"))));
    parsed(
        new ImportedFile("asset", Set.of("Current Account", "Savings"), List.of(transfer)),
        QifDateFormat.Order.DAY_MONTH);

    service().stage(upload());

    verify(importAccountRepository).upsertUnmapped(SESSION_ID, "Savings");
    verify(importCategoryRepository, never()).upsertUnmapped(anyLong(), any());
    verify(importPostingRepository)
        .insert(
            new ImportPosting(
                null,
                TX_ID,
                new BigDecimal("200.00"),
                null,
                null,
                "Savings",
                null,
                null,
                null,
                false));
    verify(importPostingRepository).insert(fundingLeg("-200.00"));
  }

  @Test
  void stagesEachSplitLegAndOneFundingLegSummingToZero() {
    openSession();
    stubFileAndTransactionInserts();
    ImportedTransaction split =
        new ImportedTransaction(
            "01/07'2004",
            "Supermarket",
            false,
            null,
            null,
            ClearedStatus.UNRECONCILED,
            false,
            List.of(
                new ImportedLine(
                    new BigDecimal("-60.00"),
                    "food",
                    null,
                    new ImportedTarget.CategoryPath("Food")),
                new ImportedLine(
                    new BigDecimal("-40.00"),
                    "fuel",
                    null,
                    new ImportedTarget.CategoryPath("Fuel"))));
    parsed(
        new ImportedFile("asset", Set.of("Current Account"), List.of(split)),
        QifDateFormat.Order.DAY_MONTH);

    service().stage(upload());

    verify(importPostingRepository).insert(categoryLeg("60.00", "food", "Food"));
    verify(importPostingRepository).insert(categoryLeg("40.00", "fuel", "Fuel"));
    verify(importPostingRepository).insert(fundingLeg("-100.00"));
  }

  @Test
  void stagesTheOpeningBalanceSelfTransferAsZeroSumPairOnTheSameAccount() {
    openSession();
    stubFileAndTransactionInserts();
    ImportedTransaction opening =
        new ImportedTransaction(
            "01/07'2004",
            null,
            false,
            null,
            null,
            ClearedStatus.UNRECONCILED,
            true,
            List.of(
                new ImportedLine(
                    new BigDecimal("1000.00"),
                    null,
                    null,
                    new ImportedTarget.AccountReference("Current Account"))));
    parsed(
        new ImportedFile("asset", Set.of("Current Account"), List.of(opening)),
        QifDateFormat.Order.DAY_MONTH);

    service().stage(upload());

    verify(importTransactionRepository)
        .insert(
            new ImportTransaction(
                null,
                FILE_ID,
                LocalDate.of(2004, 7, 1),
                null,
                false,
                null,
                null,
                "unreconciled",
                true,
                null,
                null));
    verify(importPostingRepository)
        .insert(fundingLeg("1000.00")); // the synthesised funding leg names the same account
    verify(importPostingRepository)
        .insert(
            new ImportPosting(
                null,
                TX_ID,
                new BigDecimal("-1000.00"),
                null,
                null,
                "Current Account",
                null,
                null,
                null,
                false));
  }

  @Test
  void stagedFilesAndHasStagedFileAreEmptyWithoutAnOpenSession() {
    when(importSessionService.currentSession()).thenReturn(Optional.empty());

    assertThat(service().stagedFiles()).isEmpty();
    assertThat(service().hasStagedFile("export.qif")).isFalse();
  }

  @Test
  void removeFileDelegatesToTheRepositoryAndReMatches() {
    when(importFileRepository.deleteById(FILE_ID)).thenReturn(1);

    service().removeFile(FILE_ID);

    // The orphan clean-up must run before the delete (plan e2b) — a survivor's stale
    // counter_amount would otherwise be indistinguishable from a hand-entered one (§6.4).
    verify(importMirrorMatchingService).clearOrphanedResolutionsBeforeFileRemoval(FILE_ID);
    verify(importFileRepository).deleteById(FILE_ID);
    verify(importMirrorMatchingService).rematchCurrentSession();
  }

  @Test
  void removeFileThatRemovedNothingStillCleansUpButDoesNotReMatch() {
    when(importFileRepository.deleteById(FILE_ID)).thenReturn(0);

    service().removeFile(FILE_ID);

    verify(importMirrorMatchingService).clearOrphanedResolutionsBeforeFileRemoval(FILE_ID);
    verify(importMirrorMatchingService, never()).rematchCurrentSession();
  }
}
