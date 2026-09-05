package volkovandr.hauptbuch.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;

/**
 * Re-runs transfer mirror matching over the open campaign's staging area (import.md §6.1; plan e1)
 * whenever its inputs change — a file staged or removed, an account mapping edited — so the review
 * and the commit gate always see current mirror state. The matching itself is SQL-resident in
 * {@link ImportMirrorRepository}; this is the orchestration seam its callers share. Idempotent: a
 * no-op when no campaign is open, and re-running changes nothing.
 *
 * <p>Also triggers {@link ImportCrossCurrencyRateWriteBackService} after each rematch (plan e3,
 * import.md §6.3) — every currently-resolved cross-currency leg is offered to {@code ledger}'s
 * observed-rate write-back.
 */
@Service
public class ImportMirrorMatchingService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportMirrorMatchingService.class);

  private final ImportSessionService importSessionService;
  private final ImportMirrorRepository importMirrorRepository;
  private final ImportCrossCurrencyRateWriteBackService rateWriteBackService;

  ImportMirrorMatchingService(
      ImportSessionService importSessionService,
      ImportMirrorRepository importMirrorRepository,
      ImportCrossCurrencyRateWriteBackService rateWriteBackService) {
    this.importSessionService = importSessionService;
    this.importMirrorRepository = importMirrorRepository;
    this.rateWriteBackService = rateWriteBackService;
  }

  /**
   * Re-match the open campaign's staged transfers (import.md §6.1). Clears the prior mirror marks
   * and re-computes from the current account map — the second sighting of each matched pair is
   * marked {@code mirrored} and skipped at commit. A no-op when no campaign is open.
   */
  @Transactional
  public void rematchCurrentSession() {
    importSessionService
        .currentSession()
        .ifPresent(
            session -> {
              int mirrored = importMirrorRepository.rematch(session.importSessionId());
              rateWriteBackService.writeBackObservedRates(session.importSessionId());
              LOG.debug(
                  "Import session {} mirror re-match: {} transaction(s) marked mirrored",
                  session.importSessionId(),
                  mirrored);
            });
  }

  /**
   * Clear the counter-currency amount of any surviving posting whose recorded mirror lies in {@code
   * importFileId} — call this <strong>before</strong> that file's cascade delete (plan e2b). The
   * on-delete-set-null FK (V21) already clears {@code mirror_pair_id} on the survivor; without
   * this, the now-orphaned {@code counter_amount} left behind would be indistinguishable from a
   * legitimate hand-entered far amount (§6.4 — also {@code mirror_pair_id is null}) on the next
   * {@link #rematchCurrentSession}, and could silently book a stale figure.
   */
  @Transactional
  public void clearOrphanedResolutionsBeforeFileRemoval(long importFileId) {
    importMirrorRepository.clearCounterAmountOfMirrorsIn(importFileId);
  }

  /**
   * The {@code (session, filename)} counterpart — every staged file of that name may be removed
   * (the §2 "replace" clash resolution removes them all before staging the replacement).
   */
  @Transactional
  public void clearOrphanedResolutionsBeforeFilesRemoval(long importSessionId, String filename) {
    importMirrorRepository.clearCounterAmountOfMirrorsInFilesNamed(importSessionId, filename);
  }
}
