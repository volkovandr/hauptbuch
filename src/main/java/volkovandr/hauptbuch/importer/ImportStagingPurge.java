package volkovandr.hauptbuch.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;

/**
 * Purges a campaign's staging after a successful commit (import.md §2 — "staging is cleared once,
 * after a successful commit"; plan f2). Deletes the files (transactions and postings cascade), the
 * account and category maps (tag junctions cascade), and the duplicate-scan snapshot; the {@code
 * import_session} row itself stays, now {@code committed}.
 *
 * <p>Runs <em>after</em> — not inside — the atomic commit transaction, so a failure here leaves
 * booked history intact and only orphan staging rows behind (harmless; a {@code committed} session
 * never re-commits).
 */
@Component
class ImportStagingPurge {

  private static final Logger LOG = LoggerFactory.getLogger(ImportStagingPurge.class);

  private final ImportFileRepository importFileRepository;
  private final ImportAccountRepository importAccountRepository;
  private final ImportCategoryRepository importCategoryRepository;
  private final ImportDuplicateScanService importDuplicateScanService;

  ImportStagingPurge(
      ImportFileRepository importFileRepository,
      ImportAccountRepository importAccountRepository,
      ImportCategoryRepository importCategoryRepository,
      ImportDuplicateScanService importDuplicateScanService) {
    this.importFileRepository = importFileRepository;
    this.importAccountRepository = importAccountRepository;
    this.importCategoryRepository = importCategoryRepository;
    this.importDuplicateScanService = importDuplicateScanService;
  }

  @Transactional
  void purge(long importSessionId) {
    importDuplicateScanService.clearSnapshot(importSessionId);
    importCategoryRepository.deleteBySession(importSessionId);
    importAccountRepository.deleteBySession(importSessionId);
    int files = importFileRepository.deleteBySession(importSessionId);
    LOG.info("Import session {} staging cleared after commit ({} file(s))", importSessionId, files);
  }
}
