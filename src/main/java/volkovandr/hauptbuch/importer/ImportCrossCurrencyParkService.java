package volkovandr.hauptbuch.importer;

import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.repository.ImportMirrorRepository;

/**
 * The review's cross-currency panel (import.md §9, §6.4/§6.5; plan e2b) — a focused precursor to
 * e4's full issues list. Every cross-currency transfer that automatic matching ({@link
 * ImportMirrorMatchingService}) could not resolve on its own stays {@code parked}; this class owns
 * the owner's two ways to close a park by hand: {@link #manualMatch} pairs two parked sightings the
 * automatic pass found ambiguous, and {@link #closeParkWithFarAmount} types the far amount for a
 * transfer whose counterparty's export will never arrive (§6.4). Neither path derives or guesses an
 * amount — the resolution comes from the owner or from a real mirror sighting, nothing else.
 *
 * <p>The mutations live in {@link ImportMirrorRepository}, alongside the automatic matching they
 * complement; this class is the validation and session-scoping seam its controller caller uses (the
 * {@link ImportAccountMapService} / {@link ImportAccountMapPanel} split).
 */
@Service
public class ImportCrossCurrencyParkService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCrossCurrencyParkService.class);

  private final ImportSessionService importSessionService;
  private final ImportMirrorRepository importMirrorRepository;

  ImportCrossCurrencyParkService(
      ImportSessionService importSessionService, ImportMirrorRepository importMirrorRepository) {
    this.importSessionService = importSessionService;
    this.importMirrorRepository = importMirrorRepository;
  }

  /**
   * The still-parked cross-currency transfer legs of the open campaign — empty when no campaign is
   * open or nothing is parked.
   */
  public List<ImportCrossCurrencyPark> parks() {
    return importSessionService
        .currentSession()
        .map(session -> parksForSession(session.importSessionId()))
        .orElseGet(List::of);
  }

  /**
   * The {@code parks()} counterpart for a caller that already holds the open session (the {@link
   * ImportReviewService} render-model assembler pattern) — avoids a second {@code currentSession()}
   * lookup for the same request.
   */
  public List<ImportCrossCurrencyPark> parksForSession(long importSessionId) {
    return importMirrorRepository.parkedCrossCurrencyLegs(importSessionId);
  }

  /**
   * Manually pair two parked cross-currency transfer legs as one transfer's two sightings (§6.5) —
   * the owner's resolution of an ambiguous same-day set, or a cross-currency split leg, that {@link
   * ImportMirrorMatchingService#rematchCurrentSession} could not disambiguate on its own.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the two postings do not form a valid parked crossing pair
   */
  @Transactional
  public void manualMatch(long importPostingId, long mirrorPostingId) {
    long sessionId = requireOpenSession();
    if (!importMirrorRepository.manualMatch(sessionId, importPostingId, mirrorPostingId)) {
      throw new IllegalArgumentException(
          "Postings "
              + importPostingId
              + " and "
              + mirrorPostingId
              + " are not a matchable parked cross-currency pair");
    }
    LOG.debug(
        "Import postings {} and {} manually matched as a cross-currency transfer pair",
        importPostingId,
        mirrorPostingId);
  }

  /**
   * Close a park by hand-entering the far-currency amount (§6.4) — for a transfer whose
   * counterparty's export was cleared from {@code expect-file} and will never arrive to supply a
   * real mirror.
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the amount is missing, zero, or does not match the
   *     transfer's own direction, or the posting is not a currently-parked cross-currency leg whose
   *     named account's {@code expect-file} is cleared
   */
  @Transactional
  public void closeParkWithFarAmount(long importPostingId, BigDecimal farAmount) {
    long sessionId = requireOpenSession();
    if (farAmount == null) {
      throw new IllegalArgumentException("Type the far-currency amount to close this park");
    }
    if (!importMirrorRepository.closeParkWithFarAmount(sessionId, importPostingId, farAmount)) {
      throw new IllegalArgumentException(
          "Posting "
              + importPostingId
              + " is not a closeable park — check it is still parked, its counterparty's file is"
              + " not expected, and the amount is nonzero and matches the transfer's direction");
    }
    LOG.debug("Import posting {} closed with a hand-entered far amount", importPostingId);
  }

  private long requireOpenSession() {
    return importSessionService
        .currentSession()
        .orElseThrow(() -> new IllegalStateException("No import session is open."))
        .importSessionId();
  }
}
