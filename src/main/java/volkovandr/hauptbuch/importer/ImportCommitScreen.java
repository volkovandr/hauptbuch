package volkovandr.hauptbuch.importer;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.backup.BackupFile;
import volkovandr.hauptbuch.backup.BackupKind;
import volkovandr.hauptbuch.backup.BackupService;
import volkovandr.hauptbuch.importer.ImportReviewService.CommitReadiness;
import volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository;

/**
 * Assembles the import commit screen's render model (plan f2b) — the {@link ImportReviewService}
 * panel-assembler pattern. The backup gate: a <strong>manual</strong> backup taken after the last
 * duplicate-scan run. Because the scan must be current to commit (§9) and any staging or ledger
 * change discards or stales it, "a backup after the scan" ⟹ "a backup after the last change", with
 * no timestamp column to add.
 */
@Component
class ImportCommitScreen {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  private final ImportSessionService importSessionService;
  private final ImportReviewService importReviewService;
  private final ImportDuplicateScanRepository importDuplicateScanRepository;
  private final BackupService backupService;
  private final ImportCommitWorker importCommitWorker;

  ImportCommitScreen(
      ImportSessionService importSessionService,
      ImportReviewService importReviewService,
      ImportDuplicateScanRepository importDuplicateScanRepository,
      BackupService backupService,
      ImportCommitWorker importCommitWorker) {
    this.importSessionService = importSessionService;
    this.importReviewService = importReviewService;
    this.importDuplicateScanRepository = importDuplicateScanRepository;
    this.backupService = backupService;
    this.importCommitWorker = importCommitWorker;
  }

  /** The commit screen for the open campaign, or empty when none is open. */
  Optional<ImportCommitView> forOpenSession() {
    return importSessionService.currentSession().map(this::build);
  }

  private ImportCommitView build(ImportSession session) {
    long sessionId = session.importSessionId();
    CommitReadiness readiness = importReviewService.commitReadiness();

    // The scan time is a timestamptz instant; the backup time is a wall-clock LocalDateTime decoded
    // from the filename. Compare both as wall-clock in the deployment's zone — the pre-commit
    // backup
    // and the commit are minutes apart, so the only inexactness is a ≤1h window around a DST
    // change,
    // where a spurious "not current" just prompts another backup (harmless).
    Optional<LocalDateTime> scanRanAt =
        importDuplicateScanRepository
            .findScan(sessionId)
            .map(ImportDuplicateScanRow::ranAt)
            .map(instant -> instant.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
    Optional<LocalDateTime> lastManual =
        backupService.list().stream()
            .filter(file -> file.kind() == BackupKind.MANUAL)
            .map(BackupFile::takenAt)
            .findFirst();

    boolean backupCurrent =
        scanRanAt.isPresent()
            && lastManual.isPresent()
            && lastManual.get().isAfter(scanRanAt.get());
    ImportCommitProgress progress = importCommitWorker.progressFor(sessionId);
    boolean canCommit = readiness.ready() && backupCurrent && !progress.running();

    return new ImportCommitView(
        sessionId,
        TIMESTAMP.format(session.startedAt()),
        readiness.ready(),
        readiness.scanCleared(),
        backupCurrent,
        lastManual.map(TIMESTAMP::format).orElse(null),
        scanRanAt.map(TIMESTAMP::format).orElse(null),
        canCommit,
        progress);
  }
}
