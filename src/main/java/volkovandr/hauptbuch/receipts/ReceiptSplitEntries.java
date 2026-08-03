package volkovandr.hauptbuch.receipts;

import java.util.ArrayList;
import java.util.List;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitLineDraft;

/**
 * Materialises a reviewed receipt draft as the register's own split entry (plan §9g) — the shape
 * {@code DockSplitService.commit} already knows how to book. Confirm grows <em>no</em> second
 * commit entry-point: a receipt line is a split line, the paying account is the funding leg, and
 * the mixed-split sign rule (register §3.8) signs everything, so a deposit-return line nets against
 * the groceries exactly as it does when typed into the register by hand.
 *
 * <p>The amounts are handed over as the dock's German-formatted <em>strings</em> rather than the
 * {@code numeric} values the draft stores. That is deliberate: the editor's fields already hold
 * exactly the text the operator saw and can still edit, and re-formatting it is far cheaper than
 * giving the commit path a parallel typed entry-point to keep in step forever.
 *
 * <p>Always a <em>new</em> transaction ({@code transactionId} is null) — a re-entry voids its
 * predecessor and books afresh (the settled no-drift-check, plan §9g), it never re-threads in
 * place. Single-currency by construction: the Confirm gate refuses a header currency that differs
 * from the paying account's, so no spending currency or header totals are ever set here.
 */
final class ReceiptSplitEntries {

  private ReceiptSplitEntries() {}

  /**
   * Build the split entry for a confirmed receipt.
   *
   * @param receipt the receipt as just saved — the source of the resolved header payee
   * @param form the submitted editor state, which owns the date, account, note, and lines
   */
  static SplitEntry of(Receipt receipt, ReceiptEditorForm form) {
    List<SplitLineDraft> lines = new ArrayList<>();
    for (WorkingLine line : WorkingLine.from(form)) {
      if (!line.isEmpty()) {
        lines.add(lineOf(line));
      }
    }
    return new SplitEntry(
        null,
        ReceiptEditorText.parseDate(form.date()),
        form.accountId(),
        null,
        null,
        null,
        receipt.payeeId(),
        null,
        ReceiptEditorText.blankToNull(form.note()),
        null,
        null,
        null,
        List.of(),
        lines);
  }

  /**
   * One draft line as a split line. A beneficiary line carries no id (its per-currency debt leaf is
   * provisioned at commit); a category or transfer line carries the resolved account id, and the
   * transfer direction is what tells the two apart downstream. The line's own tags ride along —
   * receipts have no transaction-level tag field, so nothing lands on the funding leg.
   */
  private static SplitLineDraft lineOf(WorkingLine line) {
    return new SplitLineDraft(
        ReceiptEditorText.parseId(line.categoryId()),
        line.amount(),
        ReceiptEditorText.blankToNull(line.note()),
        ReceiptEditorText.blankToNull(line.transferDirection()),
        ReceiptEditorText.blankToNull(line.personName()),
        ReceiptEditorText.blankToNull(line.personDirection()),
        ReceiptEditorText.blankToNull(line.personRevive()),
        line.tags());
  }
}
