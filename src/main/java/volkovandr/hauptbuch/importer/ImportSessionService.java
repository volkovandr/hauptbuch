package volkovandr.hauptbuch.importer;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.repository.ImportSessionRepository;

/**
 * The import campaign's lifecycle (import.md §2): open a session, discard it, or read the current
 * one. Enforces <strong>one open session at a time</strong> — the precondition that makes the
 * mirror rule (§6) and the commit gate (§9) well-defined. Discard is the feature's only "undo";
 * nothing here touches the ledger.
 */
@Service
public class ImportSessionService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportSessionService.class);

  private final ImportSessionRepository importSessionRepository;

  ImportSessionService(ImportSessionRepository importSessionRepository) {
    this.importSessionRepository = importSessionRepository;
  }

  /** The open campaign, or empty when none is in progress. */
  public Optional<ImportSession> currentSession() {
    return importSessionRepository.findOpen();
  }

  /**
   * Open a new import campaign.
   *
   * @throws IllegalStateException if a session is already open — every upload feeds the one open
   *     session, so a second cannot start until this one is committed or discarded (§2)
   */
  @Transactional
  public ImportSession openSession() {
    importSessionRepository
        .findOpen()
        .ifPresent(
            open -> {
              throw new IllegalStateException(
                  "An import session is already open (id "
                      + open.importSessionId()
                      + "); commit or discard it before starting another.");
            });
    ImportSession opened = importSessionRepository.insertOpen();
    LOG.info("Import session {} opened", opened.importSessionId());
    return opened;
  }

  /**
   * Discard the open campaign (§2) — its staging rows are abandoned, not unwound. A no-op when no
   * session is open.
   */
  @Transactional
  public void discardSession() {
    if (importSessionRepository.discardOpen() > 0) {
      LOG.info("Import session discarded");
    }
  }
}
