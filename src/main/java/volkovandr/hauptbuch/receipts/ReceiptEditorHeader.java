package volkovandr.hauptbuch.receipts;

/**
 * The post-process editor's header fields as one value (plan §9f) — the read-side sibling of {@link
 * ReceiptHeaderDraft}, which is the same header coerced for persistence. Both seeding a form from a
 * stored receipt and re-emitting one after an add/remove/redistribute round-trip carry the whole
 * header through untouched, so {@link WorkingLine#toForm} takes it as one argument rather than nine
 * positional strings that are trivial to transpose.
 *
 * <p>Every field is the raw text the operator typed, exactly as {@link ReceiptEditorForm} binds it
 * — coercion happens once, at Save.
 *
 * @param date raw {@code yyyy-MM-dd} booking date, or blank
 * @param payeeText the payee picker text
 * @param accountId the chosen paying account id, or null
 * @param currencyCode the header (spending) currency
 * @param total the editable total, in the receipt's own currency
 * @param fundingTotal the cross-currency total off the paying account (issue receipts/23); blank
 *     for a single-currency receipt
 * @param baseTotal the cross-currency base-currency total (issue receipts/23); blank unless neither
 *     leg is the book's base currency
 * @param note the header note, copied to {@code transaction.note} at Confirm
 * @param receiptNumber the printed receipt/Beleg number
 */
record ReceiptEditorHeader(
    String date,
    String payeeText,
    Long accountId,
    String currencyCode,
    String total,
    String fundingTotal,
    String baseTotal,
    String note,
    String receiptNumber) {

  /** The header of an already-bound form, for the round-trips that only rework the lines. */
  static ReceiptEditorHeader of(ReceiptEditorForm form) {
    return withTotals(form, form.fundingTotal(), form.baseTotal());
  }

  /**
   * The same header with the two cross-currency totals replaced by what {@code
   * SplitCurrencyService} proposed (issue receipts/23) — each either unchanged, because the
   * operator typed it, or newly filled from the rate feed.
   */
  static ReceiptEditorHeader withTotals(
      ReceiptEditorForm form, String fundingTotal, String baseTotal) {
    return new ReceiptEditorHeader(
        form.date(),
        form.payeeText(),
        form.accountId(),
        form.currencyCode(),
        form.total(),
        fundingTotal,
        baseTotal,
        form.note(),
        form.receiptNumber());
  }
}
