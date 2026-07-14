package volkovandr.hauptbuch.ledger;

/**
 * A tag carried by a transaction's postings, as the register read-side surfaces it (register §3.6,
 * plan stage 7e): the {@code tagId} the dock re-submits and the canonical {@code Parent:Child}
 * label to show.
 *
 * <p>The {@code tag} vocabulary is owned by {@code categories}; {@code ledger} owns only the {@code
 * posting_tag} linkage on its postings and reads the shared {@code tag} table (native SQL, no
 * cross-module Java dependency) to compose the label for display and for the dock's edit-mode
 * pre-fill. This is the read counterpart of {@link PostingDraft#tagIds()} on the write side.
 *
 * @param tagId the leaf tag id a posting is tagged with (data-model §10.2)
 * @param label the canonical hierarchy label, segments joined by {@code :}
 */
public record TransactionTag(long tagId, String label) {}
