package volkovandr.hauptbuch.importer;

import java.time.OffsetDateTime;

/**
 * The open campaign's current ledger-duplicate-scan snapshot (import.md §9; plan f1) — {@link
 * volkovandr.hauptbuch.importer.repository.ImportDuplicateScanRepository#findScan}'s row shape.
 * There is at most one per campaign ({@code import_duplicate_scan.import_session_id} is unique);
 * its absence means the scan has never been run, which keeps the commit gate locked.
 *
 * @param importDuplicateScanId surrogate PK
 * @param ranAt when the scan was last run — the snapshot timestamp the staleness check compares
 *     against the latest ledger mutation (Q-IMP-5)
 */
public record ImportDuplicateScanRow(long importDuplicateScanId, OffsetDateTime ranAt) {}
