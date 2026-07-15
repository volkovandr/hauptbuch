package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.MultiValueMap;
import volkovandr.hauptbuch.ledger.RegisterFilter;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Binds and reshapes the split panel's raw request parameters into a {@link SplitForm} and the
 * derived values {@link RegisterSplitController} commits (register §3.10, plan stage 7c.2/7d.2).
 * Extracted from the controller so it stays a thin set of endpoints: this is the panel's parsing
 * and re-derivation, no request handling.
 *
 * <p>Binding reads the <em>raw</em> multi-valued params rather than {@code @ModelAttribute} list
 * binding, because a split line's amount or note legitimately contains a comma (German decimals —
 * {@code 20,50} — and free text) and Spring's collection binding would split one {@code
 * lineAmount=20,50} value into two, misaligning every line array (the "one comma spawns extra
 * lines" fix).
 */
final class SplitFormBinder {

  /** German entry is to the minor unit; two places covers EUR/CHF/USD. */
  private static final int FRACTION_DIGITS = 2;

  private SplitFormBinder() {}

  /**
   * Bind the panel form from the raw request parameters (see the class note on why
   * not @ModelAttribute).
   */
  static SplitForm bind(MultiValueMap<String, String> p) {
    List<String> categoryText = orEmpty(p.get("categoryText"));
    List<String> lineCategoryId = orEmpty(p.get("lineCategoryId"));
    List<String> lineCategoryType = orEmpty(p.get("lineCategoryType"));
    List<String> lineTransferDirection = orEmpty(p.get("lineTransferDirection"));
    List<String> lineAmount = orEmpty(p.get("lineAmount"));
    List<String> lineNote = orEmpty(p.get("lineNote"));
    int lineCount =
        maxSize(
            categoryText,
            lineCategoryId,
            lineCategoryType,
            lineTransferDirection,
            lineAmount,
            lineNote);
    return new SplitForm(
        parseLong(p.getFirst("transactionId")),
        parseDate(p.getFirst("date")),
        parseLong(p.getFirst("accountId")),
        p.getFirst("payeeText"),
        p.getFirst("note"),
        p.getFirst("total"),
        p.getFirst("spendingCurrencyCode"),
        p.getFirst("fundingTotal"),
        p.getFirst("baseTotal"),
        categoryText,
        lineCategoryId,
        lineCategoryType,
        lineTransferDirection,
        lineAmount,
        lineNote,
        longValues(p.get("tagId")),
        lineTagsOf(p, lineCount),
        longValues(p.get("viewAccountId")),
        parseDate(p.getFirst("viewFromDate")),
        parseDate(p.getFirst("viewToDate")),
        parseLong(p.getFirst("viewPayeeId")));
  }

  /**
   * The per-line tag-id lists, one inner list per line (register §3.6, plan stage 7e.3). Each
   * line's committed chips submit hidden inputs named {@code lineTag{i}} (i the line index), so a
   * line's tags are read by index — like {@code /categories/resolve} names its per-line target —
   * rather than as one flat aligned array (which could not express a variable number of tags per
   * line).
   */
  private static List<List<Long>> lineTagsOf(MultiValueMap<String, String> p, int lineCount) {
    List<List<Long>> perLine = new ArrayList<>();
    for (int i = 0; i < lineCount; i++) {
      perLine.add(longValues(p.get("lineTag" + i)));
    }
    return perLine;
  }

  @SafeVarargs
  private static int maxSize(List<String>... lists) {
    int max = 0;
    for (List<String> list : lists) {
      max = Math.max(max, size(list));
    }
    return max;
  }

  /** Rebuild the form with a pre-filled base total (the rate-derived header confirmation value). */
  static SplitForm withBaseTotal(SplitForm form, String baseTotal) {
    return new SplitForm(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.payeeText(),
        form.note(),
        form.total(),
        form.spendingCurrencyCode(),
        form.fundingTotal(),
        baseTotal,
        form.categoryText(),
        form.lineCategoryId(),
        form.lineCategoryType(),
        form.lineTransferDirection(),
        form.lineAmount(),
        form.lineNote(),
        form.tagId(),
        form.lineTagIds(),
        form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }

  /** The reference total a freshly-opened panel counts against — the seed line's magnitude. */
  static String openingTotal(String amount, String type) {
    BigDecimal net;
    try {
      net = SplitLineAmounts.signedContribution(amount, type);
    } catch (IllegalArgumentException e) {
      net = BigDecimal.ZERO;
    }
    return MoneyFormat.number(net.abs(), FRACTION_DIGITS);
  }

  /** The complete lines of the form; skips fully-blank lines, rejects a line with no category. */
  static List<SplitLineDraft> linesOf(SplitForm form) {
    List<SplitLineDraft> lines = new ArrayList<>();
    int count =
        Math.max(
            size(form.lineCategoryId()), Math.max(size(form.lineAmount()), size(form.lineNote())));
    for (int i = 0; i < count; i++) {
      String idText = at(form.lineCategoryId(), i);
      String amount = at(form.lineAmount(), i);
      if (idText.isBlank() && amount.isBlank()) {
        continue; // an empty line the user never filled in
      }
      if (idText.isBlank()) {
        throw new IllegalArgumentException("Each split line needs a category (pick or create one)");
      }
      lines.add(
          new SplitLineDraft(
              Long.parseLong(idText.strip()),
              amount,
              blankToNull(at(form.lineNote(), i)),
              blankToNull(at(form.lineTransferDirection(), i)),
              tagsAt(form.lineTagIds(), i)));
    }
    return lines;
  }

  /**
   * The tag-id list for line {@code index}, index-aligned with the other line arrays; empty if
   * none.
   */
  private static List<Long> tagsAt(List<List<Long>> perLine, int index) {
    if (perLine == null || index >= perLine.size() || perLine.get(index) == null) {
      return List.of();
    }
    return perLine.get(index);
  }

  /** The active-filter view the panel carries, so a commit repaints the current register view. */
  static RegisterFilter filterFrom(SplitForm form) {
    return new RegisterFilter(
        form.viewAccountId() == null ? List.of() : form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }

  static String orEmpty(String value) {
    return value == null ? "" : value;
  }

  private static List<String> orEmpty(List<String> values) {
    return values == null ? List.of() : values;
  }

  static Long parseLong(String value) {
    return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  static String at(List<String> list, int index) {
    if (list == null || index >= list.size() || list.get(index) == null) {
      return "";
    }
    return list.get(index);
  }

  private static List<Long> longValues(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream().filter(v -> v != null && !v.isBlank()).map(Long::valueOf).toList();
  }

  private static LocalDate parseDate(String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value.strip());
  }

  private static int size(List<String> list) {
    return list == null ? 0 : list.size();
  }
}
