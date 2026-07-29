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
 * <p><strong>A person may fund the whole split</strong> (register §3.3/§3.10, issue 07) — "Max paid
 * for a whole receipt of mine" — mirroring {@link DockCommitService}'s funding-person branch. With
 * no real account in the transaction, {@code spendingCurrencyCode} becomes the <em>transaction
 * currency</em>: it sets every leg, so the split is single-currency by construction and is the only
 * currency source there is, letting a brand-new person be provisioned from the Account field.
 *
 * @param transactionId the transaction being edited, or {@code null} for a new entry
 * @param date booking date
 * @param accountId the funding (own) account the money moves through — fixes the funding currency;
 *     {@code null} when {@code fundingPersonName} names a person instead, whose leaf does not exist
 *     until commit
 * @param fundingPersonName the <em>funding</em> person's name when the split's Account field named
 *     a person rather than an account (register §3.3/§3.10, issue 07); {@code null} otherwise
 * @param fundingPersonDirection {@code FOR}/{@code BY} alongside {@code fundingPersonName}
 * @param fundingPersonRevive the Restore/Create-new decision for {@code fundingPersonName}
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
 * @param tagIds the transaction-level tags (register §3.6, plan stage 7e.3) — the split's header
 *     chip field. These land on the <em>funding</em> leg (data-model §10.2, owner decision
 *     2026-07-14); each category line's own tags live on {@link SplitLineDraft#tagIds()}. Never
 *     null; defaults empty
 * @param lines the split lines; each becomes one category leg, its amount in the spending currency
 */
public record SplitEntry(
    Long transactionId,
    LocalDate date,
    Long accountId,
    String fundingPersonName,
    String fundingPersonDirection,
    String fundingPersonRevive,
    Long payeeId,
    String payeeText,
    String note,
    String spendingCurrencyCode,
    String fundingTotal,
    String baseTotal,
    List<Long> tagIds,
    List<SplitLineDraft> lines) {

  /** Defensively copy the tag ids and lines so the entry cannot be mutated after the fact. */
  public SplitEntry {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
    lines = lines == null ? List.of() : List.copyOf(lines);
  }
}
