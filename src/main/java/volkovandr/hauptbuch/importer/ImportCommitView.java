package volkovandr.hauptbuch.importer;

/**
 * The render model for the import commit screen (plan f2b) — the {@code backup → commit → backup}
 * ceremony (import.md §2) and the commit gate, assembled by {@link ImportCommitScreen}.
 *
 * @param importSessionId the open campaign's id — the worker's handle for {@code start} / progress
 * @param campaignOpenedAt when the campaign was opened, {@code dd.MM.yyyy HH:mm}
 * @param gateReady whether the whole §9 commit gate is open (every account and category mapped, no
 *     account still awaiting its file, no cross-currency transfer parked, the scan run and every
 *     match adjudicated)
 * @param scanCleared whether the duplicate-scan condition alone holds — for a precise message
 * @param backupCurrent whether a manual backup exists that is newer than the last duplicate-scan
 *     run (⇒ newer than the last staging or ledger change, since either invalidates the scan)
 * @param lastManualBackup the newest manual backup's timestamp, {@code dd.MM.yyyy HH:mm}, or null
 * @param scanRanAt when the duplicate scan last ran, {@code dd.MM.yyyy HH:mm}, or null
 * @param canCommit gate open, a current backup taken, and no run already in flight
 * @param progress the current (or last) commit run's state
 */
public record ImportCommitView(
    long importSessionId,
    String campaignOpenedAt,
    boolean gateReady,
    boolean scanCleared,
    boolean backupCurrent,
    String lastManualBackup,
    String scanRanAt,
    boolean canCommit,
    ImportCommitProgress progress) {}
