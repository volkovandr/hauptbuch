package volkovandr.hauptbuch.operations;

import java.util.ArrayList;
import java.util.List;

/**
 * The split panel's index-aligned line arrays — reading, counting, and reshaping them (register
 * §3.10, plan stage 7c.2/7e.3). Extracted from {@link SplitPanelAssembler} so the assembler stays
 * focused on the panel's readout math; this is the mechanical list plumbing beneath it (append a
 * line, drop a line, read a cell, count the lines), including the per-line tag-id lists and the
 * new-line tag inheritance rule (§3.6).
 */
final class SplitLineArrays {

  private SplitLineArrays() {}

  /** The line count is the longest aligned array (a partially-bound form is padded with blanks). */
  static int lineCount(SplitForm form) {
    return List.of(
            size(form.categoryText()),
            size(form.lineCategoryId()),
            size(form.lineCategoryType()),
            size(form.lineTransferDirection()),
            size(form.lineAmount()),
            size(form.lineNote()))
        .stream()
        .max(Integer::compare)
        .orElse(0);
  }

  static int size(List<String> list) {
    return list == null ? 0 : list.size();
  }

  static String at(List<String> list, int index) {
    if (list == null || index >= list.size() || list.get(index) == null) {
      return "";
    }
    return list.get(index);
  }

  /** The tag-id list for line {@code index}, index-aligned with the line arrays; empty if none. */
  static List<Long> tagsAt(List<List<Long>> perLine, int index) {
    if (perLine == null || index >= perLine.size() || perLine.get(index) == null) {
      return List.of();
    }
    return perLine.get(index);
  }

  static List<String> appended(List<String> list, String value) {
    List<String> copy = new ArrayList<>(list == null ? List.of() : list);
    copy.add(value);
    return copy;
  }

  static List<String> removed(List<String> list, int index) {
    List<String> copy = new ArrayList<>(list == null ? List.of() : list);
    if (index >= 0 && index < copy.size()) {
      copy.remove(index);
    }
    return copy;
  }

  static List<List<Long>> appendedTags(List<List<Long>> perLine, List<Long> value) {
    List<List<Long>> copy = new ArrayList<>(perLine == null ? List.of() : perLine);
    copy.add(value);
    return copy;
  }

  static List<List<Long>> removedTags(List<List<Long>> perLine, int index) {
    List<List<Long>> copy = new ArrayList<>(perLine == null ? List.of() : perLine);
    if (index >= 0 && index < copy.size()) {
      copy.remove(index);
    }
    return copy;
  }

  /**
   * The tags a newly-added line inherits (register §3.6, plan stage 7e.3): the transaction-level
   * (header) tags when any were set — so every line carries them — otherwise the previous line's
   * own tags (the convenience for a run of same-tagged lines). Empty when there is neither.
   */
  static List<Long> inheritedTags(SplitForm form) {
    List<Long> header = form.tagId();
    if (header != null && !header.isEmpty()) {
      return List.copyOf(header);
    }
    List<List<Long>> lineTags = form.lineTagIds();
    if (lineTags != null && !lineTags.isEmpty()) {
      List<Long> previous = lineTags.get(lineTags.size() - 1);
      return previous == null ? List.of() : List.copyOf(previous);
    }
    return List.of();
  }

  /** Rebuild the form with the given line arrays, carrying every other field through unchanged. */
  static SplitForm withLines(
      SplitForm form,
      List<String> categoryText,
      List<String> lineCategoryId,
      List<String> lineCategoryType,
      List<String> lineTransferDirection,
      List<String> lineAmount,
      List<String> lineNote,
      List<List<Long>> lineTagIds) {
    return new SplitForm(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.payeeText(),
        form.note(),
        form.total(),
        form.spendingCurrencyCode(),
        form.fundingTotal(),
        form.baseTotal(),
        categoryText,
        lineCategoryId,
        lineCategoryType,
        lineTransferDirection,
        lineAmount,
        lineNote,
        form.tagId(),
        lineTagIds,
        form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }
}
