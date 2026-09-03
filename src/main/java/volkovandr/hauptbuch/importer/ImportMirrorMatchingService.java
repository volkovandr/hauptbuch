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
 */
@Service
public class ImportMirrorMatchingService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportMirrorMatchingService.class);

  private final ImportSessionService importSessionService;
  private final ImportMirrorRepository importMirrorRepository;

  ImportMirrorMatchingService(
      ImportSessionService importSessionService, ImportMirrorRepository importMirrorRepository) {
    this.importSessionService = importSessionService;
    this.importMirrorRepository = importMirrorRepository;
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
              LOG.debug(
                  "Import session {} mirror re-match: {} transaction(s) marked mirrored",
                  session.importSessionId(),
                  mirrored);
            });
  }
}
