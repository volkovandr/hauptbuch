package volkovandr.hauptbuch.importer;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * The commit-time ledger duplicate scan (import.md §9; plan f1) — the orchestration seam its
 * controller and {@link ImportReviewService} share, over the SQL-resident scan in {@link
 * ImportDuplicateScanRepository}. Same shape as {@link ImportCrossCurrencyParkService} / {@link
 * ImportMirrorMatchingService}: validation and session-scoping here, the mutations in the
 * repository.
 *
 * <p>{@link #runScan()} (re-)computes the snapshot on the owner's button press; {@link #adjudicate}
 * records a keep/skip decision; {@link #panelFor} assembles the review's panel and the gate
 * condition. There is no ledger lock — a decision made against a since-changed ledger transaction
 * is re-raised by the next {@link #runScan()} (Q-IMP-5).
 */
@Service
public class ImportDuplicateScanService {

  private static final Logger LOG = LoggerFactory.getLogger(ImportDuplicateScanService.class);
  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter GERMAN_TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
  private static final String IMPORT = "import";
  private static final String SKIP = "skip";
  private static final String DASH = "—";

  private final ImportSessionService importSessionService;
  private final ImportDuplicateScanRepository repository;

  ImportDuplicateScanService(
      ImportSessionService importSessionService, ImportDuplicateScanRepository repository) {
    this.importSessionService = importSessionService;
    this.repository = repository;
  }

  /**
   * Run — or re-run — the ledger duplicate scan for the open campaign (import.md §9). Re-stamps the
   * snapshot, re-detects the overlaps, and re-raises any adjudication whose ledger transaction has
   * changed since (Q-IMP-5).
   *
   * @throws IllegalStateException if no campaign is open
   */
  @Transactional
  public void runScan() {
    long sessionId = requireOpenSession();
    int matches = repository.rescan(sessionId);
    LOG.info("Import session {} duplicate scan: {} candidate match(es)", sessionId, matches);
  }

  /**
   * Record the owner's decision on one match: {@code import} books the staged transaction anyway at
   * commit, {@code skip} drops it (import.md §9).
   *
   * @throws IllegalStateException if no campaign is open
   * @throws IllegalArgumentException if the decision is not {@code import}/{@code skip}, the scan
   *     has never been run, or the match is not on the current scan
   */
  @Transactional
  public void adjudicate(long importDuplicateMatchId, String decision) {
    if (!IMPORT.equals(decision) && !SKIP.equals(decision)) {
      throw new IllegalArgumentException("Decide 'import' or 'skip' for a duplicate-scan match");
    }
    long sessionId = requireOpenSession();
    long scanId =
        repository
            .findScan(sessionId)
            .orElseThrow(
                () -> new IllegalArgumentException("Run the duplicate scan before adjudicating it"))
            .importDuplicateScanId();
    if (!repository.adjudicate(scanId, importDuplicateMatchId, decision)) {
      throw new IllegalArgumentException(
          "Match " + importDuplicateMatchId + " is not on the current duplicate scan");
    }
  }

  /**
   * Discard the campaign's scan snapshot (plan f2) — part of the commit's post-success staging
   * cleanup.
   */
  @Transactional
  public void clearSnapshot(long importSessionId) {
    repository.clearScan(importSessionId);
  }

  /**
   * The scan panel for a session (the {@link ImportReviewService} render-model assembler pattern) —
   * {@link ImportDuplicateScan#EMPTY} when the scan has never run.
   */
  public ImportDuplicateScan panelFor(long importSessionId) {
    Optional<ImportDuplicateScanRow> scan = repository.findScan(importSessionId);
    if (scan.isEmpty()) {
      return ImportDuplicateScan.EMPTY;
    }
    ImportDuplicateScanRow row = scan.get();
    boolean stale = staleAgainstActivity(row.ranAt());
    List<ImportDuplicateMatch> matches = repository.findMatchRows(row.importDuplicateScanId());
    List<ImportDuplicateScan.MatchRow> pending =
        matches.stream()
            .filter(m -> "pending".equals(m.adjudication()))
            .map(ImportDuplicateScanService::toRow)
            .toList();
    List<ImportDuplicateScan.MatchRow> adjudicated =
        matches.stream()
            .filter(m -> !"pending".equals(m.adjudication()))
            .map(ImportDuplicateScanService::toRow)
            .toList();
    return new ImportDuplicateScan(
        true, stale, GERMAN_TIMESTAMP.format(row.ranAt()), pending, adjudicated);
  }

  /**
   * Whether a ledger transaction was created / edited / voided after the scan ran — that happens on
   * another screen, so the importer cannot invalidate the snapshot at the source and catches it
   * here by time instead. Every <em>importer-side</em> change that could move a match (a file
   * staged or removed, a mapping edited, a cross-currency park resolved) discards the snapshot
   * outright ({@link ImportDuplicateScanRepository#clearScan}), so it does not need a staleness
   * check.
   */
  private boolean staleAgainstActivity(OffsetDateTime ranAt) {
    Optional<OffsetDateTime> ledgerMutation = repository.latestLedgerMutation();
    return ledgerMutation.isPresent() && ledgerMutation.get().isAfter(ranAt);
  }

  private static ImportDuplicateScan.MatchRow toRow(ImportDuplicateMatch match) {
    return new ImportDuplicateScan.MatchRow(
        match.importDuplicateMatchId(),
        match.transactionId(),
        GERMAN_DATE.format(match.date()),
        match.moneyAccountName(),
        MoneyFormat.number(match.amount(), 2),
        blankToDash(match.stagedPayee()),
        blankToDash(match.ledgerPayee()),
        blankToDash(match.ledgerNote()),
        match.adjudication());
  }

  private static String blankToDash(String value) {
    return value == null || value.isBlank() ? DASH : value;
  }

  private long requireOpenSession() {
    return importSessionService
        .currentSession()
        .orElseThrow(() -> new IllegalStateException("No import session is open."))
        .importSessionId();
  }
}
