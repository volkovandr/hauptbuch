package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;

/**
 * A single-category transaction as entered in the register's entry dock (register §3, plan stage
 * 7b/7d.1) — the raw dock fields before they are resolved into balanced postings by {@link
 * DockCommitService}. A "simple" transaction: one funding account, one category counterpart (splits
 * and transfers arrive in later sub-stages).
 *
 * <p>Payee and category each arrive as either a picked existing id <em>or</em> create-new text (the
 * datalist's "Create new…" path); the service resolves whichever is present. The funding leg's
 * amount is a bare German-formatted magnitude whose sign is <em>determined</em> by the category's
 * type, unless an explicit leading {@code +}/{@code −} overrides it (register §3.8).
 *
 * <p>Cross-currency (register §3.5/§3.8a): {@code categoryCurrencyCode} is the (possibly
 * overridden) leaf currency to post to — null/blank means "the funding account's currency", the
 * untouched single-currency path. Overriding it to a different currency declares the transaction
 * cross-currency and requires {@code categoryAmount} (the category leg's own native magnitude); if
 * neither leg is the book's base currency, {@code baseAmount} freezes the real base-currency value
 * on both legs (data-model §6.4).
 *
 * <p>Transfers (register §3.5/§3.8, plan stage 7d.3): when {@code transferDirection} is set, the
 * counterpart is a <em>real own account</em> — {@code categoryId} carries that account's id (not a
 * category), no currency leaf is resolved (its currency is fixed by the account), and the funding
 * leg's direction comes from the transfer direction ({@code TO} = outflow, {@code FROM} = inflow)
 * rather than a category type. A cross-currency transfer (the two accounts' currencies differ)
 * reuses the same {@code categoryAmount}/{@code baseAmount} machinery as a cross-currency category
 * entry.
 *
 * <p>Persons (register §3.5, plan stage 8b, data-model §7): when {@code personName}/{@code
 * personDirection} are set, the counterpart is a person's per-currency debt leaf, auto-provisioned
 * at commit — {@code categoryId} carries no value for this shape. Currency routing (the funding
 * account's currency by default, or the category-currency selector's override) is identical to the
 * plain-category branch; the funding leg's direction is {@code FOR} = outflow (you funded it),
 * {@code BY} = inflow (they funded it) — the same convention as expense/income. {@code
 * personRevive} carries the dock's Restore/Create-new decision when the name matched only a
 * soft-deleted person; irrelevant otherwise.
 *
 * <p>{@code transactionId} distinguishes the dock's two modes (register §3.1): {@code null} is a
 * <em>new</em> entry that {@link DockCommitService} records; a non-null id is an <em>edit</em> that
 * re-threads that existing transaction in place ({@code editTransaction}). Editing the account or
 * date re-threads both affected balance threads via the same bounded re-fetch as a backdated insert
 * (register §3.3).
 *
 * @param transactionId the transaction being edited, or {@code null} for a new entry
 * @param date booking date
 * @param accountId the funding (own) account the money moves through
 * @param payeeId a picked existing payee, or null
 * @param payeeText create-new payee text when no existing payee was picked; null/blank otherwise
 * @param categoryId the semantically-picked category (a leaf or a subdivided parent)
 * @param categoryCurrencyCode the leaf currency to post to; null/blank defaults to the funding
 *     account's currency
 * @param amount the funding leg's bare magnitude as typed, German-formatted, optionally with a
 *     leading {@code +}/{@code −} override
 * @param categoryAmount the category leg's own native magnitude; required only when cross-currency
 * @param baseAmount the frozen base-currency magnitude; required only when neither leg is base
 * @param note free-text transaction note; nullable
 * @param transferDirection {@code TO}/{@code FROM} when the counterpart is a transfer account
 *     (register §3.8); {@code null} for a category counterpart
 * @param personName the counterpart person's name when this entry attributes the transaction to a
 *     person (register §3.5, plan stage 8b); {@code null} otherwise
 * @param personDirection {@code FOR}/{@code BY} alongside {@code personName} (data-model §7)
 * @param personRevive the dock's Restore ({@code true}) / Create-new ({@code false}) decision for a
 *     name that matched only a soft-deleted person; irrelevant when it did not
 * @param tagIds the resolved tag ids the committed chips carry (register §3.6, plan stage 7e). A
 *     transaction-level tag lands on <em>every</em> leg (data-model §10.2), so {@link
 *     DockCommitService} attaches these to both the funding and the counterpart leg; never null,
 *     defaults empty
 */
public record DockEntry(
    Long transactionId,
    LocalDate date,
    long accountId,
    Long payeeId,
    String payeeText,
    long categoryId,
    String categoryCurrencyCode,
    String amount,
    String categoryAmount,
    String baseAmount,
    String note,
    String transferDirection,
    String personName,
    String personDirection,
    String personRevive,
    List<Long> tagIds) {

  /** Defensively copy the tag ids (null-safe) so the entry cannot be mutated after. */
  public DockEntry {
    tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
  }
}
