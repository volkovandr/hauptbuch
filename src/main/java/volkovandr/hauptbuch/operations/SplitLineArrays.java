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
            size(form.linePersonName()),
            size(form.linePersonDirection()),
            size(form.linePersonRevive()),
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

  private static List<String> appended(List<String> list, String value) {
    List<String> copy = new ArrayList<>(list == null ? List.of() : list);
    copy.add(value);
    return copy;
  }

  private static List<String> removed(List<String> list, int index) {
    List<String> copy = new ArrayList<>(list == null ? List.of() : list);
    if (index >= 0 && index < copy.size()) {
      copy.remove(index);
    }
    return copy;
  }

  private static List<List<Long>> appendedTags(List<List<Long>> perLine, List<Long> value) {
    List<List<Long>> copy = new ArrayList<>(perLine == null ? List.of() : perLine);
    copy.add(value);
    return copy;
  }

  private static List<List<Long>> removedTags(List<List<Long>> perLine, int index) {
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
  private static List<Long> inheritedTags(SplitForm form) {
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

  /**
   * Append a blank line whose amount is {@code amount}, extending every aligned array in step and
   * seeding the new line's inherited tags (§3.6). Returns a new form; every non-line field carries
   * through unchanged.
   *
   * <p>The caller names the line it wants rather than handing over the nine rebuilt arrays: the
   * arrays only stay aligned if every one of them grows, and that is this class's job to guarantee,
   * not each caller's to remember.
   */
  static SplitForm appendedLine(SplitForm form, String amount) {
    return new Lines(form)
        .map(list -> appended(list, ""))
        .withAmount(appended(form.lineAmount(), amount))
        .withTags(appendedTags(form.lineTagIds(), inheritedTags(form)))
        .applyTo(form);
  }

  /** Drop the line at {@code index} from every aligned array in step. Returns a new form. */
  static SplitForm removedLine(SplitForm form, int index) {
    return new Lines(form)
        .map(list -> removed(list, index))
        .withTags(removedTags(form.lineTagIds(), index))
        .applyTo(form);
  }

  /**
   * The ten index-aligned per-line arrays of a {@link SplitForm}, grouped so the append/remove
   * operations can reshape them in step and rebuild the form without threading eleven parameters
   * (or duplicating the 24-arg {@code SplitForm} constructor) through a helper. The nine String
   * arrays reshape uniformly via {@link #map}; the amount and tag arrays, which take a distinct
   * value, override afterwards.
   */
  private record Lines(
      List<String> categoryText,
      List<String> lineCategoryId,
      List<String> lineCategoryType,
      List<String> lineTransferDirection,
      List<String> linePersonName,
      List<String> linePersonDirection,
      List<String> linePersonRevive,
      List<String> lineAmount,
      List<String> lineNote,
      List<List<Long>> lineTagIds) {

    Lines(SplitForm form) {
      this(
          form.categoryText(),
          form.lineCategoryId(),
          form.lineCategoryType(),
          form.lineTransferDirection(),
          form.linePersonName(),
          form.linePersonDirection(),
          form.linePersonRevive(),
          form.lineAmount(),
          form.lineNote(),
          form.lineTagIds());
    }

    /** Reshape every String array (including amount) the same way; tags are handled separately. */
    Lines map(java.util.function.UnaryOperator<List<String>> op) {
      return new Lines(
          op.apply(categoryText),
          op.apply(lineCategoryId),
          op.apply(lineCategoryType),
          op.apply(lineTransferDirection),
          op.apply(linePersonName),
          op.apply(linePersonDirection),
          op.apply(linePersonRevive),
          op.apply(lineAmount),
          op.apply(lineNote),
          lineTagIds);
    }

    Lines withAmount(List<String> newLineAmount) {
      return new Lines(
          categoryText,
          lineCategoryId,
          lineCategoryType,
          lineTransferDirection,
          linePersonName,
          linePersonDirection,
          linePersonRevive,
          newLineAmount,
          lineNote,
          lineTagIds);
    }

    Lines withTags(List<List<Long>> newLineTagIds) {
      return new Lines(
          categoryText,
          lineCategoryId,
          lineCategoryType,
          lineTransferDirection,
          linePersonName,
          linePersonDirection,
          linePersonRevive,
          lineAmount,
          lineNote,
          newLineTagIds);
    }

    /** Rebuild {@code form} with these line arrays, carrying every non-line field through. */
    SplitForm applyTo(SplitForm form) {
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
          linePersonName,
          linePersonDirection,
          linePersonRevive,
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
}
