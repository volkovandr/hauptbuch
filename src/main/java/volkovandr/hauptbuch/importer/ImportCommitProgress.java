package volkovandr.hauptbuch.importer;

/**
 * The import commit run's state, for the screen that polls it (plan f2b). Held in memory by {@link
 * ImportCommitWorker} and stamped with the session it ran for, so a fresh campaign's screen never
 * shows a stale panel. The commit is one atomic transaction, so a JVM death mid-run rolls the
 * ledger back and leaves nothing to resume.
 *
 * @param status one of {@code idle} / {@code running} / {@code done} / {@code failed}
 * @param importSessionId the campaign this run belongs to; {@code 0} for {@link #IDLE}
 * @param total the number of staged transactions the run will process (book or skip)
 * @param done how many it has processed so far
 * @param message the closing summary ({@code done}) or the failure reason ({@code failed}), else
 *     null
 */
public record ImportCommitProgress(
    String status, long importSessionId, int total, int done, String message) {

  static final ImportCommitProgress IDLE = new ImportCommitProgress("idle", 0L, 0, 0, null);

  static ImportCommitProgress ofRunning(long importSessionId, int total, int done) {
    return new ImportCommitProgress("running", importSessionId, total, done, null);
  }

  static ImportCommitProgress ofDone(long importSessionId, String message) {
    return new ImportCommitProgress("done", importSessionId, 0, 0, message);
  }

  static ImportCommitProgress ofFailed(long importSessionId, String message) {
    return new ImportCommitProgress("failed", importSessionId, 0, 0, message);
  }

  /** Whether a run is in flight — the screen keeps polling and the Commit button stays disabled. */
  public boolean running() {
    return "running".equals(status);
  }

  /** Whether the last run finished successfully. */
  public boolean succeeded() {
    return "done".equals(status);
  }

  /** Whether the last run failed — the ledger is unchanged and staging is intact. */
  public boolean failed() {
    return "failed".equals(status);
  }

  /** The percentage complete, for the progress bar; 0 when {@code total} is not yet known. */
  public int percent() {
    return total <= 0 ? 0 : Math.min(100, done * 100 / total);
  }
}
