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
 * @param total the reference funding magnitude the "remaining" readout counts against (German-
 *     formatted); the committed funding leg is the signed sum of the lines, which may differ (the
 *     "Save and update amount" path)
 * @param categoryText each line's category text, for re-prefill and the per-line resolve call
 * @param lineCategoryId each line's resolved category id ({@code ""} until resolved)
 * @param lineCategoryType each line's resolved category type ({@code income}/{@code expense},
 *     {@code ""} until resolved) — drives the live direction/remaining readout; the commit
 *     re-resolves authoritatively
 * @param lineAmount each line's typed amount (a bare magnitude, optionally a leading {@code −}
 *     storno)
 * @param lineNote each line's posting-level note; nullable per line
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
    List<String> categoryText,
    List<String> lineCategoryId,
    List<String> lineCategoryType,
    List<String> lineAmount,
    List<String> lineNote,
    List<Long> viewAccountId,
    LocalDate viewFromDate,
    LocalDate viewToDate,
    Long viewPayeeId) {

  /** Defensively copy the mutable list fields (null-safe) so the form cannot be mutated after. */
  public SplitForm {
    categoryText = categoryText == null ? null : List.copyOf(categoryText);
    lineCategoryId = lineCategoryId == null ? null : List.copyOf(lineCategoryId);
    lineCategoryType = lineCategoryType == null ? null : List.copyOf(lineCategoryType);
    lineAmount = lineAmount == null ? null : List.copyOf(lineAmount);
    lineNote = lineNote == null ? null : List.copyOf(lineNote);
    viewAccountId = viewAccountId == null ? null : List.copyOf(viewAccountId);
  }
}
