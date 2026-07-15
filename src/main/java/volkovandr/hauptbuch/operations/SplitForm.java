package volkovandr.hauptbuch.operations;

import java.time.LocalDate;
import java.util.List;

/**
 * The split panel's form fields, bound in one shot (register §3.10, plan stage 7c.2) — the panel's
 * transaction-level inputs, the index-aligned per-line arrays, and the active-filter {@code view*}
 * hidden fields it carries (like the simple dock) so a commit or a re-render repaints the current
 * view. The panel form is the single source of truth: every field is submitted <em>and</em>
 * re-emitted on a server re-render, so resolved ids survive add/remove-line.
 *
 * <p>The five line arrays are index-aligned — element {@code i} of each describes line {@code i}.
 * They bind as {@code String} lists (not typed) because an unresolved line carries an empty
 * category id/type until {@code /categories/resolve} fills it, and an empty string binds cleanly to
 * a {@code String} slot where it would fail a {@code Long}. The category <em>text</em> input is
 * named {@code categoryText} (not {@code lineCategoryText}) so a line's own change event posts it
 * to the shared {@code /categories/resolve} endpoint verbatim — the same param the simple dock
 * uses.
 *
 * @param transactionId the transaction being edited; {@code null} for a new split (register §3.1)
 * @param date booking date
 * @param accountId the funding account — fixes the split's currency
 * @param payeeText the payee text (a picked datalist value or a create-new string); nullable
 * @param note transaction-level note; nullable
 * @param total the reference total the lines subdivide, in the <em>spending</em> currency (German-
 *     formatted). For a single-currency split this is the funding magnitude the "remaining" readout
 *     counts against; when cross-currency (§3.8a) it is the spending-currency receipt total the
 *     lines sum to, alongside {@code fundingTotal} and {@code baseTotal}
 * @param spendingCurrencyCode the one currency the lines are denominated in (register §3.5, §3.10);
 *     null/blank means the funding account's currency — the untouched single-currency split
 * @param fundingTotal the funding-currency total off the account (the frozen funding-leg magnitude,
 *     §3.8a); blank unless cross-currency
 * @param baseTotal the base-currency total (the frozen funding-leg base magnitude); blank unless
 *     cross-currency and neither leg is the book's base currency
 * @param categoryText each line's category text, for re-prefill and the per-line resolve call
 * @param lineCategoryId each line's resolved category id ({@code ""} until resolved)
 * @param lineCategoryType each line's resolved category type ({@code income}/{@code expense},
 *     {@code ""} until resolved) — drives the live direction/remaining readout; the commit
 *     re-resolves authoritatively
 * @param lineTransferDirection each line's transfer direction ({@code TO}/{@code FROM}, {@code ""}
 *     for an ordinary category line) — set when the line resolved to a {@code To →}/{@code From ←}
 *     transfer target (register §3.8, plan stage 7d.3); index-aligned like the others, so every
 *     line emits it (blank for a category) to keep the arrays aligned
 * @param lineAmount each line's typed amount (a bare magnitude, optionally a leading {@code −}
 *     storno)
 * @param lineNote each line's posting-level note; nullable per line
 * @param tagId the transaction-level (header) tag ids — the split's own chip field, one hidden
 *     input per pill (register §3.6, plan stage 7e.3). Land on the funding leg (data-model §10.2)
 * @param lineTagIds each line's own tag ids, index-aligned with the line arrays — the per-line
 *     chips (register §3.6, plan stage 7e.3). Bound from the raw {@code lineTag{i}} params (a list
 *     of lists, one inner list per line), which is why they are not one flat array like the others
 * @param viewAccountId the active filter's viewed accounts; empty for the default set
 * @param viewFromDate the active filter's lower date bound; nullable
 * @param viewToDate the active filter's upper date bound; nullable
 * @param viewPayeeId the active filter's payee; nullable
 */
public record SplitForm(
    Long transactionId,
    LocalDate date,
    Long accountId,
    String payeeText,
    String note,
    String total,
    String spendingCurrencyCode,
    String fundingTotal,
    String baseTotal,
    List<String> categoryText,
    List<String> lineCategoryId,
    List<String> lineCategoryType,
    List<String> lineTransferDirection,
    List<String> lineAmount,
    List<String> lineNote,
    List<Long> tagId,
    List<List<Long>> lineTagIds,
    List<Long> viewAccountId,
    LocalDate viewFromDate,
    LocalDate viewToDate,
    Long viewPayeeId) {

  /** Defensively copy the mutable list fields (null-safe) so the form cannot be mutated after. */
  public SplitForm {
    categoryText = categoryText == null ? null : List.copyOf(categoryText);
    lineCategoryId = lineCategoryId == null ? null : List.copyOf(lineCategoryId);
    lineCategoryType = lineCategoryType == null ? null : List.copyOf(lineCategoryType);
    lineTransferDirection =
        lineTransferDirection == null ? null : List.copyOf(lineTransferDirection);
    lineAmount = lineAmount == null ? null : List.copyOf(lineAmount);
    lineNote = lineNote == null ? null : List.copyOf(lineNote);
    tagId = tagId == null ? null : List.copyOf(tagId);
    lineTagIds =
        lineTagIds == null
            ? null
            : List.copyOf(lineTagIds.stream().map(SplitForm::copyOf).toList());
    viewAccountId = viewAccountId == null ? null : List.copyOf(viewAccountId);
  }

  /** Null-safe immutable copy of one line's tag-id list (an unresolved line may bind a null). */
  private static List<Long> copyOf(List<Long> ids) {
    return ids == null ? List.of() : List.copyOf(ids);
  }
}
