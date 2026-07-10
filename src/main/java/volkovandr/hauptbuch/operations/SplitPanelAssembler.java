package volkovandr.hauptbuch.operations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import volkovandr.hauptbuch.shared.MoneyFormat;

/**
 * Builds the {@link SplitPanel} view model from a {@link SplitForm} on every server round-trip of
 * the split panel (register §3.10, plan stage 7c.2) — open, add-line, remove-line, and error
 * redisplay — and owns the "the rest" defaulting when a line is added.
 *
 * <p>The readout math mirrors {@link DockSplitService}'s commit math (the mixed-split rule ratified
 * 2026-07-09) but <em>leniently</em>: an incomplete line (no amount yet, or an unresolved category)
 * simply contributes nothing, so the panel renders sensibly mid-entry. The commit path re-derives
 * the same numbers authoritatively from the resolved leaves — these are a display convenience the
 * keyboard.js leaf also computes live as the user types (§1.7).
 */
@Component
class SplitPanelAssembler {

  /** German entry is to the minor unit; two places covers EUR/CHF. */
  private static final int FRACTION_DIGITS = 2;

  /** Build the panel view model for the current form state, optionally carrying a message. */
  SplitPanel panel(SplitForm form, String error) {
    int count = lineCount(form);
    List<SplitLineView> lines = new ArrayList<>();
    BigDecimal net = BigDecimal.ZERO;
    for (int i = 0; i < count; i++) {
      String amount = at(form.lineAmount(), i);
      String type = at(form.lineCategoryType(), i);
      net = net.add(lenientContribution(amount, type));
      lines.add(
          new SplitLineView(
              i,
              at(form.categoryText(), i),
              at(form.lineCategoryId(), i),
              type,
              amount,
              at(form.lineNote(), i)));
    }

    BigDecimal total = lenientParse(form.total());
    BigDecimal remaining = total.subtract(net.abs());
    return new SplitPanel(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.payeeText(),
        form.note(),
        MoneyFormat.number(total, FRACTION_DIGITS),
        lines,
        MoneyFormat.number(remaining, FRACTION_DIGITS),
        remaining.signum() == 0,
        MoneyFormat.number(net.abs(), FRACTION_DIGITS),
        direction(net),
        error);
  }

  /**
   * Append a blank line whose amount defaults to "the rest" — {@code total − allocated} (register
   * §3.10) — so the last line closes the gap. Returns a new form; the caller re-renders it.
   */
  SplitForm addLine(SplitForm form) {
    SplitPanel current = panel(form, null);
    BigDecimal remaining = lenientParse(current.remaining());
    String rest = remaining.signum() > 0 ? current.remaining() : "";
    return withLines(
        form,
        appended(form.categoryText(), ""),
        appended(form.lineCategoryId(), ""),
        appended(form.lineCategoryType(), ""),
        appended(form.lineAmount(), rest),
        appended(form.lineNote(), ""));
  }

  /** Remove the line at {@code index} across every aligned array. Returns a new form. */
  SplitForm removeLine(SplitForm form, int index) {
    return withLines(
        form,
        removed(form.categoryText(), index),
        removed(form.lineCategoryId(), index),
        removed(form.lineCategoryType(), index),
        removed(form.lineAmount(), index),
        removed(form.lineNote(), index));
  }

  /** The signed contribution, or zero for an incomplete line (blank amount or unresolved type). */
  private static BigDecimal lenientContribution(String amount, String type) {
    if (amount == null || amount.isBlank() || type == null || type.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return DockSplitService.signedContribution(amount, type);
    } catch (IllegalArgumentException e) {
      return BigDecimal.ZERO; // mid-entry unparseable text contributes nothing to the readout
    }
  }

  private static BigDecimal lenientParse(String text) {
    if (text == null || text.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return MoneyFormat.parse(text);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static String direction(BigDecimal net) {
    int sign = net.signum();
    if (sign < 0) {
      return "pay";
    }
    return sign > 0 ? "receive" : "none";
  }

  /** The line count is the longest aligned array (a partially-bound form is padded with blanks). */
  private static int lineCount(SplitForm form) {
    return List.of(
            size(form.categoryText()),
            size(form.lineCategoryId()),
            size(form.lineCategoryType()),
            size(form.lineAmount()),
            size(form.lineNote()))
        .stream()
        .max(Integer::compare)
        .orElse(0);
  }

  private static int size(List<String> list) {
    return list == null ? 0 : list.size();
  }

  private static String at(List<String> list, int index) {
    if (list == null || index >= list.size() || list.get(index) == null) {
      return "";
    }
    return list.get(index);
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

  private static SplitForm withLines(
      SplitForm form,
      List<String> categoryText,
      List<String> lineCategoryId,
      List<String> lineCategoryType,
      List<String> lineAmount,
      List<String> lineNote) {
    return new SplitForm(
        form.transactionId(),
        form.date(),
        form.accountId(),
        form.payeeText(),
        form.note(),
        form.total(),
        categoryText,
        lineCategoryId,
        lineCategoryType,
        lineAmount,
        lineNote,
        form.viewAccountId(),
        form.viewFromDate(),
        form.viewToDate(),
        form.viewPayeeId());
  }
}
