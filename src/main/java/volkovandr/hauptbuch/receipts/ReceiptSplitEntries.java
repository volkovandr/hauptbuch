package volkovandr.hauptbuch.receipts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import volkovandr.hauptbuch.operations.SplitEntry;
import volkovandr.hauptbuch.operations.SplitLineAmounts;
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
        mergedLines(lines));
  }

  /**
   * Sum lines that resolve to the same posting identity into one draft (issue 15): grouping by
   * every field that would otherwise land on a distinct posting — the category/transfer-target id,
   * transfer direction, attributed person and their direction/revive choice, tag set, and note — so
   * a receipt's repeated same-category items (the common case) book as a single summed posting
   * instead of one per line, while any line carrying its own tag or note (operator-added detail
   * that summing would otherwise discard) always keeps its own posting. First-occurrence order is
   * preserved. A group whose lines net to exactly zero (e.g. an item fully cancelled by its own
   * storno) is dropped rather than booked as a meaningless zero-amount posting. Scoped to the
   * receipt-confirm path only — {@code DockSplitService}, the shared commit path a manually-typed
   * register split also uses, receives already-merged drafts and takes no change of its own.
   */
  private static List<SplitLineDraft> mergedLines(List<SplitLineDraft> lines) {
    Map<MergeKey, SplitLineDraft> merged = new LinkedHashMap<>();
    for (SplitLineDraft line : lines) {
      merged.merge(MergeKey.of(line), line, ReceiptSplitEntries::combine);
    }
    return merged.values().stream().filter(ReceiptSplitEntries::isNonZero).toList();
  }

  private static boolean isNonZero(SplitLineDraft line) {
    return SplitLineAmounts.parseSignedAmount(line.amount()).signum() != 0;
  }

  /** Sum two same-key drafts' typed amounts; every other field is identical by construction. */
  private static SplitLineDraft combine(SplitLineDraft first, SplitLineDraft second) {
    BigDecimal sum =
        SplitLineAmounts.parseSignedAmount(first.amount())
            .add(SplitLineAmounts.parseSignedAmount(second.amount()));
    return new SplitLineDraft(
        first.categoryId(),
        SplitLineAmounts.formatSignedAmount(sum),
        first.note(),
        first.transferDirection(),
        first.personName(),
        first.personDirection(),
        first.personRevive(),
        first.tagIds());
  }

  /** Everything about a draft that determines which posting it books to, except its amount. */
  private record MergeKey(
      Long categoryId,
      String transferDirection,
      String personName,
      String personDirection,
      String personRevive,
      String note,
      Set<Long> tags) {
    static MergeKey of(SplitLineDraft line) {
      return new MergeKey(
          line.categoryId(),
          line.transferDirection(),
          line.personName(),
          line.personDirection(),
          line.personRevive(),
          line.note(),
          new TreeSet<>(line.tagIds()));
    }
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
