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
 * <p><strong>Cross-currency (register §3.8a/§3.10, plan stage 7d.2).</strong> A single receipt is
 * one merchant billing one currency, paid from one account at one rate, so a split spans at most
 * two currencies — the funding account's and the one <em>spending</em> currency — fixed once at the
 * header, never per line. When {@code spendingCurrencyCode} names a currency other than the funding
 * account's, the split is cross-currency: each line's {@link SplitLineDraft#amount()} is in that
 * spending currency, the header carries the funding-currency total ({@code fundingTotal}) and —
 * when neither the funding nor the spending currency is the book's base — the base-currency total
 * ({@code baseTotal}), and the funding leg is pinned to those totals while the category legs
 * balance in base by construction (data-model §6.4, owner-decided 2026-07-13). Null/blank {@code
 * spendingCurrencyCode} is the untouched single-currency split (the funding account's currency).
 *
 * @param transactionId the transaction being edited, or {@code null} for a new entry
 * @param date booking date
 * @param accountId the funding (own) account the money moves through — fixes the funding currency
 * @param payeeId a picked existing payee, or null
 * @param payeeText create-new payee text when no existing payee was picked; null/blank otherwise
 * @param note transaction-level note (register §3.7); nullable — the per-line notes live on the
 *     lines
 * @param spendingCurrencyCode the one spending currency the lines are denominated in; null/blank
 *     means the funding account's currency (single-currency split)
 * @param fundingTotal the funding-currency total off the account (the frozen funding-leg
 *     magnitude); required only when cross-currency
 * @param baseTotal the base-currency total (the frozen funding-leg base magnitude); required only
 *     when cross-currency and neither leg is the book's base currency
 * @param lines the split lines; each becomes one category leg, its amount in the spending currency
 */
public record SplitEntry(
    Long transactionId,
    LocalDate date,
    long accountId,
    Long payeeId,
    String payeeText,
    String note,
    String spendingCurrencyCode,
    String fundingTotal,
    String baseTotal,
    List<SplitLineDraft> lines) {

  /** Defensively copy the lines so the entry cannot be mutated after the fact. */
  public SplitEntry {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
