package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;

/**
 * A split transaction as entered in the register's split panel (register §3.10, plan stage 7c.2) —
 * the raw dock fields plus the per-line list, before {@link DockSplitService} resolves them into
 * balanced postings. One receipt is always <em>one</em> transaction, even when it mixes expense and
 * income lines (e.g. food plus a bottle-deposit return); the funding leg absorbs the signed sum of
 * the lines and the whole thing balances by construction (the mixed-split rule ratified
 * 2026-07-09).
 *
 * <p>{@code transactionId} distinguishes the panel's two modes exactly as the simple dock does
 * (register §3.1): {@code null} records a new transaction; a non-null id re-threads that existing
 * one in place ({@code editTransaction}).
 *
 * @param transactionId the transaction being edited, or {@code null} for a new entry
 * @param date booking date
 * @param accountId the funding (own) account the money moves through — fixes the split's currency
 * @param payeeId a picked existing payee, or null
 * @param payeeText create-new payee text when no existing payee was picked; null/blank otherwise
 * @param note transaction-level note (register §3.7); nullable — the per-line notes live on the
 *     lines
 * @param lines the split lines; each becomes one category leg
 */
public record SplitEntry(
    Long transactionId,
    LocalDate date,
    long accountId,
    Long payeeId,
    String payeeText,
    String note,
    List<SplitLineDraft> lines) {

  /** Defensively copy the lines so the entry cannot be mutated after the fact. */
  public SplitEntry {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
