package volkovandr.hauptbuch.operations;

import java.time.LocalDate;

/**
 * A single-category transaction as entered in the register's entry dock (register §3, plan stage
 * 7b) — the raw dock fields before they are resolved into balanced postings by {@link
 * DockCommitService}. A "simple" transaction: one funding account, one category counterpart
 * (splits, transfers, and cross-currency arrive in later sub-stages).
 *
 * <p>Payee and category each arrive as either a picked existing id <em>or</em> create-new text (the
 * datalist's "Create new…" path); the service resolves whichever is present. The amount is a bare
 * German-formatted magnitude whose sign is <em>determined</em> by the category's type, unless an
 * explicit leading {@code +}/{@code −} overrides it (register §3.8).
 *
 * @param date booking date
 * @param accountId the funding (own) account the money moves through
 * @param payeeId a picked existing payee, or null
 * @param payeeText create-new payee text when no existing payee was picked; null/blank otherwise
 * @param categoryId the semantically-picked category (a leaf or a subdivided parent)
 * @param amount the bare magnitude as typed, German-formatted, optionally with a leading {@code
 *     +}/{@code −} override
 * @param note free-text transaction note; nullable
 */
public record DockEntry(
    LocalDate date,
    long accountId,
    Long payeeId,
    String payeeText,
    long categoryId,
    String amount,
    String note) {}
