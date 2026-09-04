package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.ImportPreviewService.Parsed;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;
import volkovandr.hauptbuch.importer.repository.ImportPostingRepository;
import volkovandr.hauptbuch.importer.repository.ImportTransactionRepository;

/**
 * Stages one confirmed upload into the campaign (import.md §2, §11; plan b3): writes {@code
 * import_file} + {@code import_transaction} + {@code import_posting}, and folds the file's account
 * names and category paths into the {@code import_account} / {@code import_category} maps as
 * <strong>unmapped</strong> rows, accumulating across every file in the session (§5). Nothing
 * reaches {@code transaction} / {@code posting} — the ledger is untouched until the commit (f2).
 *
 * <p>The posting legs are written in Hauptbuch's sign convention, not Money's (see {@link
 * ImportPosting}): the funding leg on the file's own account carries the record total verbatim and
 * every category / transfer leg carries the negation of its Money amount, so a staged transaction's
 * legs sum to zero. The targets stay the unresolved Money strings — slice c/d resolve them, f2
 * books through {@code LedgerService}.
 */
@Service
public class ImportStagingService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportStagingService.class);

  private final ImportSessionService importSessionService;
  private final ImportPreviewService importPreviewService;
  private final ImportFileRepository importFileRepository;
  private final ImportTransactionRepository importTransactionRepository;
  private final ImportPostingRepository importPostingRepository;
  private final ImportAccountRepository importAccountRepository;
  private final ImportCategoryRepository importCategoryRepository;
  private final ImportMirrorMatchingService importMirrorMatchingService;

  ImportStagingService(
      ImportSessionService importSessionService,
      ImportPreviewService importPreviewService,
      ImportFileRepository importFileRepository,
      ImportTransactionRepository importTransactionRepository,
      ImportPostingRepository importPostingRepository,
      ImportAccountRepository importAccountRepository,
      ImportCategoryRepository importCategoryRepository,
      ImportMirrorMatchingService importMirrorMatchingService) {
    this.importSessionService = importSessionService;
    this.importPreviewService = importPreviewService;
    this.importFileRepository = importFileRepository;
    this.importTransactionRepository = importTransactionRepository;
    this.importPostingRepository = importPostingRepository;
    this.importAccountRepository = importAccountRepository;
    this.importCategoryRepository = importCategoryRepository;
    this.importMirrorMatchingService = importMirrorMatchingService;
  }

  /** The staged files of the open campaign, oldest first; empty when no campaign is open. */
  public List<ImportFile> stagedFiles() {
    return importSessionService
        .currentSession()
        .map(session -> importFileRepository.findBySession(session.importSessionId()))
        .orElseGet(List::of);
  }

  /** Whether the open campaign already has a staged file of this name (§2 clash check). */
  public boolean hasStagedFile(String filename) {
    return importSessionService
        .currentSession()
        .map(
            session ->
                importFileRepository.existsBySessionAndFilename(
                    session.importSessionId(), filename))
        .orElse(false);
  }

  /**
   * Remove one staged file and everything it staged — its {@code import_transaction} and {@code
   * import_posting} rows cascade (V19). The accumulated map rows persist for the campaign (§5). A
   * no-op when the id is unknown.
   */
  @Transactional
  public void removeFile(long importFileId) {
    if (importFileRepository.deleteById(importFileId) > 0) {
      LOG.info("Import file {} removed from staging", importFileId);
      // A removed file can strand a surviving transfer's mirror (its partner leg is gone, V21 nulls
      // the link) — re-match resets any now-unpaired sighting (import.md §6.1; plan e1).
      importMirrorMatchingService.rematchCurrentSession();
    }
  }

  /**
   * Remove every staged file of the given name in the open campaign — the "replace" half of the §2
   * clash resolution (b3). Rows affected.
   */
  @Transactional
  public int removeFilesNamed(String filename) {
    int removed =
        importSessionService
            .currentSession()
            .map(
                session ->
                    importFileRepository.deleteBySessionAndFilename(
                        session.importSessionId(), filename))
            .orElse(0);
    if (removed > 0) {
      importMirrorMatchingService.rematchCurrentSession();
    }
    return removed;
  }

  /**
   * Stage a confirmed upload. Propagates {@link QifRejectedException} for a file the parser refuses
   * (§4.5), one whose date order is still ambiguous, or one the owner has not yet named an account
   * for (import.md §4.1 — a file with no opening-balance record to deduce it from).
   *
   * @throws IllegalStateException if no import session is open
   */
  @Transactional
  public ImportFile stage(PendingImportUpload upload) {
    ImportSession session =
        importSessionService
            .currentSession()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No import session is open — start one before staging a file."));
    long sessionId = session.importSessionId();

    if (upload.moneyAccountName() == null) {
      throw new QifRejectedException(
          "State which Money account this file is for before staging — it has no opening-balance"
              + " record to name it (import.md §4.1).");
    }

    Parsed parsed = importPreviewService.parse(upload);
    if (parsed.effectiveOrder() == QifDateFormat.Order.AMBIGUOUS) {
      throw new QifRejectedException(
          "This file's dates do not distinguish DD/MM from MM/DD — choose a date order before"
              + " staging (import.md §4.3).");
    }
    ImportedFile file = parsed.file();

    ImportFile stagedFile =
        importFileRepository.insert(
            new ImportFile(
                null,
                sessionId,
                upload.sourceFilename(),
                upload.moneyAccountName(),
                parsed.charsetCode(),
                parsed.dateOrderCode(),
                file.proposedAccountType(),
                file.transactions().size(),
                null));

    file.referencedAccountNames()
        .forEach(name -> importAccountRepository.upsertUnmapped(sessionId, name));
    for (ImportedTransaction transaction : file.transactions()) {
      stageTransaction(
          sessionId,
          stagedFile.importFileId(),
          upload.moneyAccountName(),
          parsed.effectiveOrder(),
          transaction);
    }

    LOG.info(
        "Import session {} staged \"{}\" for account \"{}\" — {} transactions",
        sessionId,
        upload.sourceFilename(),
        upload.moneyAccountName(),
        file.transactions().size());
    // A newly staged file can supply the second sighting of a transfer already staged from another
    // account's file — re-match against the current account map (import.md §6.1; plan e1).
    importMirrorMatchingService.rematchCurrentSession();
    return stagedFile;
  }

  private void stageTransaction(
      long sessionId,
      long importFileId,
      String fundingAccountName,
      QifDateFormat.Order order,
      ImportedTransaction transaction) {
    LocalDate date = QifDateFormat.toLocalDate(transaction.rawDate(), order);
    long importTransactionId =
        importTransactionRepository.insert(
            new ImportTransaction(
                null,
                importFileId,
                date,
                transaction.payeeText(),
                transaction.payeeDestroyed(),
                transaction.memo(),
                transaction.referenceNumber(),
                transaction.clearedStatus().stagingCode(),
                transaction.openingBalance(),
                null,
                null));

    BigDecimal fundingAmount = BigDecimal.ZERO;
    for (ImportedLine line : transaction.lines()) {
      stageLeg(sessionId, importTransactionId, line);
      fundingAmount = fundingAmount.add(line.amount());
    }
    // The funding leg names the file's own account and carries the transaction total — the sum of
    // the line amounts, which the parser has already checked equals Money's T (§7). The legs then
    // sum to zero in Hauptbuch's sign convention.
    importPostingRepository.insert(
        new ImportPosting(
            null,
            importTransactionId,
            fundingAmount,
            null,
            null,
            fundingAccountName,
            null,
            null,
            null,
            true));
  }

  private void stageLeg(long sessionId, long importTransactionId, ImportedLine line) {
    BigDecimal amount = line.amount().negate();
    switch (line.target()) {
      case ImportedTarget.CategoryPath category -> {
        importCategoryRepository.upsertUnmapped(sessionId, category.path());
        importPostingRepository.insert(
            new ImportPosting(
                null,
                importTransactionId,
                amount,
                line.memo(),
                category.path(),
                null,
                line.className(),
                null,
                null,
                false));
      }
      case ImportedTarget.AccountReference reference ->
          importPostingRepository.insert(
              new ImportPosting(
                  null,
                  importTransactionId,
                  amount,
                  line.memo(),
                  null,
                  reference.accountName(),
                  line.className(),
                  null,
                  null,
                  false));
    }
  }
}
