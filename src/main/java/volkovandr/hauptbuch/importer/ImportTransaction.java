package volkovandr.hauptbuch.importer;

import java.time.LocalDate;

/**
 * A staged transaction (import.md §11; plan b3). Carries no amount (data-model §3.5) — that lives
 * in the {@link ImportPosting} legs. The date is parsed with the file's confirmed day/month order
 * (§4.3); {@code note} is the header {@code M} memo and {@code referenceNumber} the {@code N}
 * field, kept apart here and combined into {@code transaction.note} only at commit (§4.2). Nothing
 * is booked to the ledger until the commit (f2), so {@code transactionId} stays null through every
 * step before it.
 *
 * @param importTransactionId surrogate PK; null for a not-yet-persisted row
 * @param importFileId the file this transaction was staged from
 * @param date the transaction date, parsed with the file's confirmed order (§4.3)
 * @param payeeText the {@code P} field verbatim, or null when absent or wholly destroyed (§4.4)
 * @param payeeDestroyed true when {@code P} was present but wholly {@code ?}/whitespace (§5.3)
 * @param note the header {@code M} memo, or null
 * @param referenceNumber the {@code N} field, or null — prefixed into the note at commit (§4.2)
 * @param clearedStatus {@code unreconciled} / {@code cleared} / {@code reconciled} (§4.2), applied
 *     to every leg at commit
 * @param openingBalance true for Money's opening-balance self-transfer (§5.1), reconciled at c3
 * @param state {@code ready} / {@code parked} / {@code mirrored} / {@code excluded} (§6); b3 stages
 *     every row {@code ready}
 * @param transactionId the booked {@code transaction} row once committed (f2), else null
 */
public record ImportTransaction(
    Long importTransactionId,
    Long importFileId,
    LocalDate date,
    String payeeText,
    boolean payeeDestroyed,
    String note,
    String referenceNumber,
    String clearedStatus,
    boolean openingBalance,
    String state,
    Long transactionId) {}
