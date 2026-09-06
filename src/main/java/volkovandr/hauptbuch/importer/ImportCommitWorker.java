package volkovandr.hauptbuch.importer;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.backup.BackupKind;
import volkovandr.hauptbuch.backup.BackupService;
import volkovandr.hauptbuch.importer.ImportCommitService.CommitResult;

/**
 * Runs the import commit (plan f2b) on a background thread while the screen polls for progress —
 * the {@code ReceiptBatchAnalyser} pattern (a dedicated single-thread executor, an outer guard that
 * records any failure rather than swallowing it). Progress is held in memory only and scoped to the
 * session it ran for: {@link ImportCommitService#commit} is one atomic transaction, so a JVM death
 * mid-run rolls the ledger back and the owner simply re-clicks Commit — there is nothing to resume.
 *
 * <p>On success the worker completes the {@code backup → commit → backup} ceremony (import.md §2):
 * the <em>pre</em>-backup is the owner's own click (gated on the screen); this clears staging and
 * takes the closing backup — the new baseline. A failure of that cleanup does <em>not</em> reopen
 * the commit — the ledger is already written — it is reported as done-with-a-warning.
 */
// DoNotUseThreads / AvoidCatchingGenericException: the ratified pattern is a dedicated executor off
// the request thread, with a broad guard so an unexpected error still lands as a terminal state.
@SuppressWarnings({"PMD.DoNotUseThreads", "PMD.AvoidCatchingGenericException"})
@Component
class ImportCommitWorker {

  private static final Logger LOG = LoggerFactory.getLogger(ImportCommitWorker.class);

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "import-commit");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicReference<ImportCommitProgress> state =
      new AtomicReference<>(ImportCommitProgress.IDLE);

  private final ImportCommitService importCommitService;
  private final BackupService backupService;

  ImportCommitWorker(ImportCommitService importCommitService, BackupService backupService) {
    this.importCommitService = importCommitService;
    this.backupService = backupService;
  }

  /**
   * The current run's state, but only for {@code importSessionId} — a run left over from an earlier
   * campaign reads as {@link ImportCommitProgress#IDLE}, so a fresh campaign's screen never shows a
   * stale success/failure panel.
   */
  ImportCommitProgress progressFor(long importSessionId) {
    ImportCommitProgress current = state.get();
    return current.importSessionId() == importSessionId ? current : ImportCommitProgress.IDLE;
  }

  /** The raw state, session-agnostic — for the tests. */
  ImportCommitProgress progress() {
    return state.get();
  }

  /**
   * Queue a commit run for {@code importSessionId}, unless one is already in flight. Returns
   * immediately; nothing runs on the request thread. {@code false} means a run is already going.
   */
  boolean start(long importSessionId) {
    ImportCommitProgress current = state.get();
    if (current.running()) {
      return false;
    }
    int total = importCommitService.committableCount();
    if (!state.compareAndSet(current, ImportCommitProgress.ofRunning(importSessionId, total, 0))) {
      return false;
    }
    executor.execute(this::run);
    return true;
  }

  /**
   * The run body — package-visible so tests drive it synchronously (the {@code submit} pattern).
   */
  void run() {
    long sessionId = state.get().importSessionId();
    CommitResult result;
    try {
      result =
          importCommitService.commit(
              done ->
                  state.updateAndGet(
                      p -> ImportCommitProgress.ofRunning(p.importSessionId(), p.total(), done)));
    } catch (RuntimeException e) {
      LOG.error("Import commit failed — the ledger is unchanged, staging is intact", e);
      state.set(ImportCommitProgress.ofFailed(sessionId, e.getMessage()));
      return;
    }

    // Committed. Nothing from here can un-commit it — a cleanup or backup failure is a warning, not
    // a failed commit, and must still leave a terminal state so the screen stops polling.
    String note =
        String.format(
            "Committed %d transaction(s), skipped %d, %d opening balance(s).",
            result.booked(), result.skipped(), result.openingBalances());
    try {
      importCommitService.clearStaging(result.importSessionId());
      backupService.take(BackupKind.MANUAL);
      state.set(ImportCommitProgress.ofDone(sessionId, note));
    } catch (RuntimeException e) {
      LOG.warn("Commit succeeded; the post-commit staging purge or closing backup did not", e);
      state.set(
          ImportCommitProgress.ofDone(
              sessionId,
              note
                  + " The staging purge or closing backup did not complete — check the logs and"
                  + " take a backup by hand."));
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
